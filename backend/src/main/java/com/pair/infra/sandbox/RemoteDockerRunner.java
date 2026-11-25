package com.pair.infra.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.global.config.PairProperties;

/**
 * 코드 실행을 별도 샌드박스 서버(실행 전용 API)에 맡긴다. 앱 서버에는 DB 접속 정보와 API 키가 있으므로,
 * 응시자 코드는 그런 비밀이 없는 서버에서만 돌린다. 설정 {@code pair.run.mode=remote} 일 때 쓴다.
 * 샌드박스 서버는 {@code SandboxServerConfig} 로 띄우고, 거기서 {@link SandboxRunner} 가 컨테이너를 만든다.
 * <p>
 * 샌드박스 서버가 꽉 찼거나(503) 닿지 않으면 {@link SandboxRunner.BusyException} 으로 바꿔 던진다. 화면에는 같은 503 안내가 나간다.
 */
@Component
@ConditionalOnProperty(prefix = "pair.run", name = "mode", havingValue = "remote")
public class RemoteDockerRunner implements CodeRunner {

    /** 컨테이너 시작·컴파일·네트워크에 더 기다려 주는 시간(초). 서버 쪽 대기·실행 시간 위에 더한다 */
    private static final int MARGIN_SECONDS = 30;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;
    private final Duration requestTimeout;

    public RemoteDockerRunner(PairProperties props) {
        PairProperties.Run run = props.run();
        if (run.remoteUrl() == null || run.remoteUrl().isBlank()) {
            throw new IllegalStateException("pair.run.mode=remote 에는 샌드박스 서버 주소(pair.run.remote-url, PAIR_RUN_REMOTE_URL)가 필요합니다.");
        }
        if (run.remoteToken() == null || run.remoteToken().isBlank()) {
            throw new IllegalStateException("pair.run.mode=remote 에는 공유 토큰(pair.run.remote-token, PAIR_RUN_REMOTE_TOKEN)이 필요합니다.");
        }
        this.baseUrl = run.remoteUrl().replaceAll("/+$", "");
        this.token = run.remoteToken();
        // 서버가 자리를 기다리는 시간 + 컨테이너 실행 시간 + 여유
        this.requestTimeout = Duration.ofSeconds((long) run.queueWaitSeconds() + run.timeoutSeconds() + MARGIN_SECONDS);
    }

    @Override
    public RunResult run(Map<String, String> sources) {
        return post("/internal/run", sources, RunResult.class);
    }

    @Override
    public BuildResult build(Map<String, String> sources) {
        return post("/internal/build", sources, BuildResult.class);
    }

    private <T> T post(String path, Map<String, String> sources, Class<T> type) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("sources", sources))))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) return mapper.readValue(res.body(), type);
            String message = messageOf(res.body());
            if (res.statusCode() == 503) throw new SandboxRunner.BusyException(message);
            // 토큰 불일치 같은 설정 오류는 응시자에게 사유를 보여 줄 일이 아니라 서버 오류(500)로 남긴다
            throw new UncheckedIOException(new IOException("샌드박스 서버 오류 (" + res.statusCode() + "): " + message));
        } catch (HttpTimeoutException e) {
            throw new SandboxRunner.BusyException("실행 서버의 응답이 늦어 실행하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        } catch (IOException e) {
            if (e instanceof java.net.ConnectException || e instanceof java.nio.channels.ClosedChannelException) {
                throw new SandboxRunner.BusyException("실행 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String messageOf(String body) {
        try {
            return mapper.readTree(body).path("message").asText(body);
        } catch (IOException e) {
            return body;
        }
    }
}
