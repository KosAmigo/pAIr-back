package com.pair.infra.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.global.config.VisionProperties;

import jakarta.annotation.PreDestroy;

/** HTTP session control and persistent /v1/stream connection to pair-vision. */
@Component
@Conditional(InferenceUrlConfigured.class)
public class RunPodInferenceClient implements InferenceClient {
    private static final Logger log = LoggerFactory.getLogger(RunPodInferenceClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int FRAME_HEADER_BYTES = 30;

    private final VisionProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private volatile Listener listener;

    public RunPodInferenceClient(VisionProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public StartResult start(String sessionKey, Mode mode, long startedAtMs, boolean recalibrate) {
        ObjectNode body = json.createObjectNode().put("mode", mode == Mode.CHECK ? "check" : "exam")
                .put("startedAtMs", startedAtMs).put("recalibrate", recalibrate);
        try {
            JsonNode response = post("/v1/sessions/" + sessionKey + "/start", body, true);
            if (response == null || !response.path("accepted").asBoolean(false)) return StartResult.OVER_CAPACITY;
            return StartResult.ACCEPTED;
        } catch (InferenceRequestException e) {
            if (e.status == 503) return StartResult.DRAINING;
            throw e;
        }
    }

    @Override
    public boolean changeMode(String sessionKey, long startedAtMs) {
        ObjectNode body = json.createObjectNode().put("mode", "exam").put("startedAtMs", startedAtMs);
        try {
            post("/v1/sessions/" + sessionKey + "/mode", body, false);
            return true;
        } catch (InferenceRequestException e) {
            if (e.status == 404) return false;
            throw e;
        }
    }

    @Override
    public void end(String sessionKey) {
        post("/v1/sessions/" + sessionKey + "/end", json.createObjectNode(), false);
    }

    @Override
    public void sendFrame(String sessionKey, Channel channel, long seq, long recvMs, byte[] jpeg) {
        WebSocket ws = connectedSocket();
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(sessionKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid inference session key", e);
        }
        if (key.length != 16 || jpeg.length + FRAME_HEADER_BYTES > 2_000_000) {
            throw new IllegalArgumentException("Invalid inference frame");
        }
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + jpeg.length);
        frame.put((byte) 1).put((byte) channel.code()).putInt((int) seq).putLong(recvMs).put(key).put(jpeg).flip();
        try {
            ws.sendBinary(frame, true).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            socket.compareAndSet(ws, null);
            throw new InferenceRequestException("Inference frame send failed", 0, e);
        }
    }

    @Override
    public boolean ready() {
        try {
            HttpRequest request = request("/readyz").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            JsonNode body = json.readTree(response.body());
            return body.path("modelsLoaded").asBoolean(false) && connectedSocket() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setListener(Listener listener) { this.listener = listener; }

    private JsonNode post(String path, ObjectNode body, boolean capacityAware) {
        try {
            HttpRequest request = request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (capacityAware && response.statusCode() == 503) {
                String text = response.body();
                if (text.contains("OVER_CAPACITY")) return json.createObjectNode().put("accepted", false);
                if (text.contains("DRAINING")) throw new InferenceRequestException("Inference server draining", 503, null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new InferenceRequestException("Inference request rejected", response.statusCode(), null);
            }
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (InferenceRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new InferenceRequestException("Inference request failed", 0, e);
        }
    }

    private HttpRequest.Builder request(String path) {
        String base = props.inferenceUrl().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + props.inferenceKey());
    }

    private synchronized WebSocket connectedSocket() {
        WebSocket current = socket.get();
        if (current != null && !current.isOutputClosed()) return current;
        String base = props.inferenceUrl().replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://")
                .replaceAll("/+$", "");
        try {
            WebSocket created = http.newWebSocketBuilder().connectTimeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + props.inferenceKey())
                    .buildAsync(URI.create(base + "/v1/stream"), new StreamListener()).get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            socket.set(created);
            return created;
        } catch (Exception e) {
            throw new InferenceRequestException("Inference stream unavailable", 0, e);
        }
    }

    private final class StreamListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try { dispatch(json.readTree(text.toString())); }
                catch (Exception e) { log.debug("Invalid inference stream message"); }
                text.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket.compareAndSet(webSocket, null);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            socket.compareAndSet(webSocket, null);
            log.warn("Inference stream disconnected: {}", error.getClass().getSimpleName());
        }
    }

    private void dispatch(JsonNode message) {
        Listener target = listener;
        if (target == null) return;
        String key = message.path("sessionKey").asText("");
        switch (message.path("type").asText()) {
            case "state" -> target.onState(key);
            case "check" -> target.onCheck(key, message);
            case "error" -> target.onError(key, message.path("code").asText("UNKNOWN"));
            default -> { }
        }
    }

    @PreDestroy
    void close() {
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join(); }
        catch (RuntimeException ignored) { }
    }

    private static final class InferenceRequestException extends RuntimeException {
        final int status;
        InferenceRequestException(String message, int status, Throwable cause) { super(message, cause); this.status = status; }
    }
}
