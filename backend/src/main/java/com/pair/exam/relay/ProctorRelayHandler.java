package com.pair.exam.relay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.exam.ExamProperties;

import jakarta.annotation.PreDestroy;

/**
 * 휴대폰 보조 캠 중계 (/ws/proctor). 휴대폰이 보낸 JPEG 프레임(binary)을 응시자 PC 로 그대로 넘긴다.
 * 서버는 프레임을 분석하지도 저장하지도 않고, 로그에도 남기지 않는다. PC 가 느리면 오래된 프레임은 버리고 최신 한 장만 보낸다.
 * 짝(pair)은 메모리에만 있다: 서버가 여러 대가 되면 같은 짝이 같은 서버로 붙도록 하거나 공유 저장소가 필요하다 (지금은 한 대 기준).
 *
 * 메시지 (JSON 텍스트):
 *  PC → hello-pc {invite[, practice]}      서버 → paired {pairCode, phoneUrl}, peer {phone: connected|disconnected|stale}
 *  휴대폰 → hello-phone {pairCode}         서버 → phone-joined
 *  휴대폰 → heartbeat (5초마다 생존 신호), phone-status {status} (PC 로 그대로 전달), binary JPEG (PC 로 전달)
 *  오류: {type:error, message} 후 연결을 닫는다
 */
@Component
public class ProctorRelayHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ProctorRelayHandler.class);
    private static final Pattern PAIR_CODE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    /** 사전 테스트(연습) 키: 이 브라우저가 만든 값이라 입장 토큰 확인 없이 붙는다. 어떤 기록도 저장하지 않는다 */
    private static final Pattern PRACTICE_KEY = Pattern.compile("practice-[a-f0-9]{42}");
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private static final String ROLE = "relay.role";
    private static final String PAIR = "relay.pair";
    private static final String PHONE_ROLE = "phone";
    private static final String PC_ROLE = "pc";

    enum PhoneState { CONNECTED, DISCONNECTED, STALE;
        String wire() { return name().toLowerCase(); }
    }

    /** 응시자 한 명의 PC·휴대폰 짝 */
    static final class Pair {
        final String invite;
        final String code;
        final LatestFrameMailbox mailbox = new LatestFrameMailbox();
        volatile WebSocketSession pc;
        volatile WebSocketSession phone;
        volatile PhoneState phoneState = PhoneState.DISCONNECTED;
        volatile long lastPhoneSeen;
        volatile long emptySince;

        Pair(String invite, String code) {
            this.invite = invite;
            this.code = code;
        }
    }

    private final RelayAuthorizer authorizer;
    private final ExamProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pair> byInvite = new ConcurrentHashMap<>();
    private final Map<String, Pair> byCode = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    public ProctorRelayHandler(RelayAuthorizer authorizer, ExamProperties props, ObjectMapper json, Clock clock) {
        this.authorizer = authorizer;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        // 여러 스레드(휴대폰 수신, 점검 타이머)가 한 연결에 보내므로 동시 전송을 직렬화하고, 느린 연결은 끊는다
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        raw.getAttributes().put("relay.session", session);
        limitBuffers(raw);
    }

    /** 컨테이너 기본(8KB)은 프레임(장당 13~15KB)보다 작아서, 이 연결에 한해 프레임 한도(100KB)까지 받게 한다 */
    private void limitBuffers(WebSocketSession raw) {
        if (raw instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session s = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (s != null) {
                s.setMaxBinaryMessageBufferSize(RelayLimits.MAX_FRAME_BYTES + 1024);
                s.setMaxTextMessageBufferSize(RelayLimits.MAX_TEXT_BYTES * 2);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        if (message.getPayloadLength() > RelayLimits.MAX_TEXT_BYTES) return;
        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (IOException e) {
            return;
        }
        String type = msg.path("type").asText("");
        WebSocketSession session = decorated(raw);
        switch (type) {
            case "hello-pc" -> helloPc(raw, session, msg);
            case "hello-phone" -> helloPhone(raw, session, msg);
            case "heartbeat" -> touchPhone(raw);
            case "phone-status" -> {
                Pair pair = phonePair(raw);
                if (pair == null) return;
                touchPhone(raw);
                if (msg.has("status")) sendJson(pair.pc, json.createObjectNode().put("type", "phone-status").set("status", msg.get("status")));
            }
            default -> { /* 모르는 메시지는 무시한다 */ }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) {
        Pair pair = phonePair(raw);
        if (pair == null) return;
        ByteBuffer buf = message.getPayload();
        int size = buf.remaining();
        if (size > RelayLimits.MAX_FRAME_BYTES || size < 4) return;
        byte[] frame = new byte[size];
        buf.get(frame);
        boolean jpeg = (frame[0] & 0xFF) == 0xFF && (frame[1] & 0xFF) == 0xD8 && (frame[2] & 0xFF) == 0xFF;
        if (!jpeg) return;
        touchPhone(raw);
        if (pair.mailbox.offer(frame)) sender.execute(() -> drain(pair));
    }

    /** 우편함이 빌 때까지 PC 로 최신 프레임을 보낸다. 느린 PC 에서는 그동안 쌓인 프레임이 덮어써져 버려진다 */
    private void drain(Pair pair) {
        byte[] frame;
        while ((frame = pair.mailbox.take()) != null) {
            WebSocketSession pc = pair.pc;
            if (pc == null || !pc.isOpen()) continue; // PC 가 없으면 버린다
            try {
                pc.sendMessage(new BinaryMessage(frame));
            } catch (IOException | RuntimeException e) {
                log.debug("PC 로 프레임 전송 실패: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void helloPc(WebSocketSession raw, WebSocketSession session, JsonNode msg) {
        String invite = msg.path("invite").asText("");
        boolean practice = msg.path("practice").asBoolean(false) && PRACTICE_KEY.matcher(invite).matches();
        if (!practice && !authorizer.authorize(invite)) {
            fail(raw, session, "입장 링크가 올바르지 않습니다.");
            return;
        }
        Pair pair = byInvite.computeIfAbsent(invite, k -> {
            Pair created = new Pair(k, newCode());
            byCode.put(created.code, created);
            return created;
        });
        WebSocketSession previous = pair.pc;
        if (previous != null && previous != session) closeQuietly(previous);
        pair.pc = session;
        pair.emptySince = 0;
        raw.getAttributes().put(ROLE, PC_ROLE);
        raw.getAttributes().put(PAIR, pair);
        sendJson(session, json.createObjectNode().put("type", "paired").put("pairCode", pair.code)
                .put("phoneUrl", props.entryBaseUrl().replaceAll("/+$", "") + "/proctor/phone?p=" + pair.code));
        sendPeer(pair);
        log.info("중계: PC 연결 (짝 {})", mask(pair.code));
    }

    private void helloPhone(WebSocketSession raw, WebSocketSession session, JsonNode msg) {
        String code = msg.path("pairCode").asText("");
        Pair pair = PAIR_CODE.matcher(code).matches() ? byCode.get(code) : null;
        if (pair == null) {
            fail(raw, session, "연결 정보가 올바르지 않습니다. PC 화면의 QR 을 다시 찍어 주세요.");
            return;
        }
        WebSocketSession previous = pair.phone;
        if (previous != null && previous != session) closeQuietly(previous);
        pair.phone = session;
        pair.emptySince = 0;
        pair.lastPhoneSeen = clock.millis();
        raw.getAttributes().put(ROLE, PHONE_ROLE);
        raw.getAttributes().put(PAIR, pair);
        sendJson(session, json.createObjectNode().put("type", "phone-joined"));
        setPhoneState(pair, PhoneState.CONNECTED);
        log.info("중계: 휴대폰 연결 (짝 {})", mask(pair.code));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Pair pair = (Pair) raw.getAttributes().get(PAIR);
        if (pair == null) return;
        WebSocketSession mine = decorated(raw);
        String role = (String) raw.getAttributes().get(ROLE);
        if (PHONE_ROLE.equals(role) && pair.phone == mine) {
            pair.phone = null;
            setPhoneState(pair, PhoneState.DISCONNECTED);
        } else if (PC_ROLE.equals(role) && pair.pc == mine) {
            pair.pc = null;
        }
        if (pair.pc == null && pair.phone == null) pair.emptySince = clock.millis();
    }

    /** 휴대폰이 감시 중인데 신호가 끊기면 PC 에 알린다. 둘 다 없는 짝은 일정 시간 뒤 지운다 */
    @Scheduled(fixedDelay = 2_000)
    void checkStale() {
        long now = clock.millis();
        for (Pair pair : byInvite.values()) {
            if (pair.phone != null && pair.phoneState == PhoneState.CONNECTED && now - pair.lastPhoneSeen > RelayLimits.PHONE_STALE_MS) {
                setPhoneState(pair, PhoneState.STALE);
            }
            if (pair.pc == null && pair.phone == null && pair.emptySince > 0 && now - pair.emptySince > RelayLimits.EMPTY_PAIR_TTL_MS) {
                byInvite.remove(pair.invite, pair);
                byCode.remove(pair.code, pair);
            }
        }
    }

    private void touchPhone(WebSocketSession raw) {
        Pair pair = phonePair(raw);
        if (pair == null) return;
        pair.lastPhoneSeen = clock.millis();
        if (pair.phoneState != PhoneState.CONNECTED) setPhoneState(pair, PhoneState.CONNECTED);
    }

    private Pair phonePair(WebSocketSession raw) {
        return PHONE_ROLE.equals(raw.getAttributes().get(ROLE)) ? (Pair) raw.getAttributes().get(PAIR) : null;
    }

    private void setPhoneState(Pair pair, PhoneState state) {
        if (pair.phoneState == state) return;
        pair.phoneState = state;
        sendPeer(pair);
    }

    private void sendPeer(Pair pair) {
        sendJson(pair.pc, json.createObjectNode().put("type", "peer").put("phone", pair.phoneState.wire()));
    }

    private void sendJson(WebSocketSession to, ObjectNode body) {
        if (to == null || !to.isOpen()) return;
        try {
            to.sendMessage(new TextMessage(json.writeValueAsString(body)));
        } catch (IOException | RuntimeException e) {
            log.debug("중계 메시지 전송 실패: {}", e.getClass().getSimpleName());
        }
    }

    private void fail(WebSocketSession raw, WebSocketSession session, String message) {
        sendJson(session, json.createObjectNode().put("type", "error").put("message", message));
        closeQuietly(raw);
    }

    private void closeQuietly(WebSocketSession s) {
        try {
            s.close();
        } catch (IOException | RuntimeException ignored) {
            // 이미 닫혔다
        }
    }

    private WebSocketSession decorated(WebSocketSession raw) {
        Object d = raw.getAttributes().get("relay.session");
        return d instanceof WebSocketSession s ? s : raw;
    }

    private String newCode() {
        byte[] b = new byte[18];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String mask(String code) {
        return code.length() <= 4 ? "****" : code.substring(0, 4) + "…";
    }

    @PreDestroy
    void shutdown() {
        sender.shutdownNow();
    }
}
