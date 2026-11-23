package com.pair.domain.exam.controller;

import com.pair.domain.exam.service.FrameRateLimiter;
import com.pair.domain.exam.service.LatestFrameMailbox;
import com.pair.domain.exam.service.RelayAuthorizer;
import com.pair.domain.exam.service.RelayLimits;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.pair.global.config.ExamProperties;

import jakarta.annotation.PreDestroy;

/**
 * 휴대폰 보조 캠 중계 (/ws/proctor). 휴대폰이 보낸 JPEG 프레임(binary)을 응시자 PC 로 그대로 넘긴다.
 * 서버는 프레임을 분석하지도 저장하지도 않고, 로그에도 남기지 않는다. PC 가 느리면 오래된 프레임은 버리고 최신 한 장만 보낸다.
 * 짝(pair)은 메모리에만 있다: 서버가 여러 대가 되면 같은 짝이 같은 서버로 붙도록 하거나 공유 저장소가 필요하다 (지금은 한 대 기준).
 * 자원 보호: 전체 연결·짝·휴대폰 수 상한, 휴대폰 1대당 초당 프레임 상한(초과분은 버리고 심하면 끊음), hello 제한 시간, 소켓당 hello 1회.
 * 상한 값은 RelayLimits 의 임시값이며 구현 뒤 실측으로 정한다.
 *
 * 메시지 (JSON 텍스트):
 *  PC → hello-pc {invite[, practice]}      서버 → paired {pairCode, phoneUrl}, peer {phone: connected|disconnected|stale}
 *  휴대폰 → hello-phone {pairCode}         서버 → phone-joined
 *  휴대폰 → heartbeat (5초마다 생존 신호), phone-status {status} (숫자·참거짓·짧은 문자열만 PC 로 전달), binary JPEG (PC 로 전달)
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
    private static final String DECORATED = "relay.session";
    private static final String COUNTED = "relay.counted";
    private static final String LIMITER = "relay.limiter";
    private static final String PHONE_ROLE = "phone";
    private static final String PC_ROLE = "pc";
    private static final String BUSY = "지금은 연결이 많아 잠시 뒤 다시 시도해 주세요.";

    enum PhoneState { CONNECTED, DISCONNECTED, STALE;
        String wire() { return name().toLowerCase(); }
    }

    /** 응시자 한 명의 PC·휴대폰 짝. pc·phone 의 바꿔 끼움과 정리는 pair 를 잠그고 한다 */
    static final class Pair {
        final String invite;
        final String code;
        final boolean practice;
        final LatestFrameMailbox mailbox = new LatestFrameMailbox();
        volatile WebSocketSession pc;
        volatile WebSocketSession phone;
        volatile PhoneState phoneState = PhoneState.DISCONNECTED;
        volatile long lastPhoneSeen;
        volatile long emptySince;
        /** 맵에서 지워진 짝. 지워진 뒤에 붙으려는 연결은 새 짝을 만든다 */
        boolean removed;

        Pair(String invite, String code, boolean practice) {
            this.invite = invite;
            this.code = code;
            this.practice = practice;
        }
    }

    private final RelayAuthorizer authorizer;
    private final ExamProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pair> byInvite = new ConcurrentHashMap<>();
    private final Map<String, Pair> byCode = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger phones = new AtomicInteger();
    /** 아직 hello 를 보내지 않은 연결과 연결 시각 (제한 시간 안에 안 보내면 닫는다) */
    private final Map<WebSocketSession, Long> unauthenticated = new ConcurrentHashMap<>();
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
        raw.getAttributes().put(DECORATED, new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT));
        if (connections.incrementAndGet() > RelayLimits.MAX_CONNECTIONS) {
            connections.decrementAndGet();
            closeQuietly(raw);
            return;
        }
        raw.getAttributes().put(COUNTED, Boolean.TRUE);
        unauthenticated.put(raw, clock.millis());
    }

    /** 컨테이너 기본(8KB)은 프레임(장당 13~15KB)보다 작아서, 인증된 휴대폰 연결에 한해 프레임 한도까지 받게 한다 */
    private void allowFrameBuffers(WebSocketSession raw) {
        if (raw instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session s = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (s != null) s.setMaxBinaryMessageBufferSize(RelayLimits.MAX_FRAME_BYTES + 1024);
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
        boolean greeted = raw.getAttributes().get(ROLE) != null;
        switch (type) {
            case "hello-pc" -> { if (!greeted) helloPc(raw, session, msg); } // 소켓당 hello 는 한 번만
            case "hello-phone" -> { if (!greeted) helloPhone(raw, session, msg); }
            case "heartbeat" -> touchPhone(raw);
            case "phone-status" -> {
                Pair pair = phonePair(raw);
                if (pair == null) return;
                touchPhone(raw);
                if (msg.has("status") && msg.get("status").isObject()) {
                    sendJson(pair.pc, json.createObjectNode().put("type", "phone-status").set("status", scalarsOnly(msg.get("status"))));
                }
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
        FrameRateLimiter limiter = (FrameRateLimiter) raw.getAttributes().get(LIMITER);
        if (limiter != null && !limiter.allow(clock.millis())) {
            if (limiter.abusive()) closeQuietly(raw); // 초당 상한을 크게 넘는 연결은 끊는다
            return;
        }
        byte[] frame = new byte[size];
        buf.get(frame);
        boolean jpeg = (frame[0] & 0xFF) == 0xFF && (frame[1] & 0xFF) == 0xD8 && (frame[2] & 0xFF) == 0xFF;
        if (!jpeg) return;
        touchPhone(raw);
        if (pair.mailbox.offer(frame)) {
            try {
                sender.execute(() -> drain(pair));
            } catch (RuntimeException rejected) {
                pair.mailbox.reset(); // 종료 중이라 못 보냈다. 전송 상태를 되돌려 우편함이 막히지 않게 한다
            }
        }
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
        Pair pair = pairFor(invite, practice);
        if (pair == null) {
            fail(raw, session, BUSY);
            return;
        }
        WebSocketSession previous;
        synchronized (pair) {
            if (pair.removed) { // 정리 직전에 걸렸다. 새로 만든다
                pair = pairFor(invite, practice);
                if (pair == null) {
                    fail(raw, session, BUSY);
                    return;
                }
            }
            previous = pair.pc;
            pair.pc = session;
            pair.emptySince = 0;
        }
        if (previous != null && previous != session) closeQuietly(previous);
        unauthenticated.remove(raw);
        raw.getAttributes().put(ROLE, PC_ROLE);
        raw.getAttributes().put(PAIR, pair);
        sendJson(session, json.createObjectNode().put("type", "paired").put("pairCode", pair.code)
                .put("phoneUrl", props.entryBaseUrl().replaceAll("/+$", "") + "/proctor/phone?p=" + pair.code));
        sendPeer(pair);
        log.info("중계: PC 연결 (짝 {})", mask(pair.code));
    }

    /** 입장 토큰의 짝을 찾거나 만든다. 서버 전체 짝 수·연습 짝 수 상한을 넘으면 null */
    private Pair pairFor(String invite, boolean practice) {
        Pair existing = byInvite.get(invite);
        if (existing != null) return existing;
        if (byInvite.size() >= RelayLimits.MAX_PAIRS || practice && practiceCount() >= RelayLimits.MAX_PRACTICE_PAIRS) return null;
        return byInvite.computeIfAbsent(invite, k -> {
            Pair created = new Pair(k, newCode(), practice);
            byCode.put(created.code, created);
            return created;
        });
    }

    private void helloPhone(WebSocketSession raw, WebSocketSession session, JsonNode msg) {
        String code = msg.path("pairCode").asText("");
        Pair pair = PAIR_CODE.matcher(code).matches() ? byCode.get(code) : null;
        if (pair == null) {
            fail(raw, session, "연결 정보가 올바르지 않습니다. PC 화면의 QR 을 다시 찍어 주세요.");
            return;
        }
        WebSocketSession previous;
        synchronized (pair) {
            if (pair.removed) {
                fail(raw, session, "연결 정보가 올바르지 않습니다. PC 화면의 QR 을 다시 찍어 주세요.");
                return;
            }
            if (pair.phone == null && phones.get() >= RelayLimits.MAX_PHONES) {
                fail(raw, session, BUSY);
                return;
            }
            previous = pair.phone;
            pair.phone = session;
            pair.emptySince = 0;
            pair.lastPhoneSeen = clock.millis();
        }
        phones.incrementAndGet(); // 닫히는 연결은 afterConnectionClosed 에서 각자 한 번씩 뺀다
        if (previous != null && previous != session) closeQuietly(previous);
        unauthenticated.remove(raw);
        raw.getAttributes().put(ROLE, PHONE_ROLE);
        raw.getAttributes().put(PAIR, pair);
        raw.getAttributes().put(LIMITER, new FrameRateLimiter(RelayLimits.MAX_FRAMES_PER_SEC, RelayLimits.MAX_EXCESS_PER_SEC));
        allowFrameBuffers(raw);
        sendJson(session, json.createObjectNode().put("type", "phone-joined"));
        setPhoneState(pair, PhoneState.CONNECTED);
        log.info("중계: 휴대폰 연결 (짝 {})", mask(pair.code));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        unauthenticated.remove(raw);
        if (raw.getAttributes().remove(COUNTED) != null) connections.decrementAndGet();
        Pair pair = (Pair) raw.getAttributes().get(PAIR);
        if (pair == null) return;
        WebSocketSession mine = decorated(raw);
        String role = (String) raw.getAttributes().get(ROLE);
        boolean phoneLeft = false;
        synchronized (pair) {
            if (PHONE_ROLE.equals(role) && pair.phone == mine) {
                pair.phone = null;
                phoneLeft = true;
            } else if (PC_ROLE.equals(role) && pair.pc == mine) {
                pair.pc = null;
            }
            if (pair.pc == null && pair.phone == null) pair.emptySince = clock.millis();
        }
        if (PHONE_ROLE.equals(role)) phones.decrementAndGet(); // 교체된 이전 연결은 새 연결이 대신 세므로 여기서 한 번만 뺀다
        if (phoneLeft) setPhoneState(pair, PhoneState.DISCONNECTED);
    }

    /** 휴대폰이 감시 중인데 신호가 끊기면 PC 에 알린다. hello 를 안 보낸 연결을 닫고, 둘 다 없는 짝은 일정 시간 뒤 지운다 */
    @Scheduled(fixedDelay = 2_000)
    void checkStale() {
        long now = clock.millis();
        unauthenticated.forEach((session, since) -> {
            if (now - since > RelayLimits.HELLO_TIMEOUT_MS) closeQuietly(session);
        });
        for (Pair pair : byInvite.values()) {
            if (pair.phone != null && pair.phoneState == PhoneState.CONNECTED && now - pair.lastPhoneSeen > RelayLimits.PHONE_STALE_MS) {
                setPhoneState(pair, PhoneState.STALE);
            }
            long ttl = pair.practice ? RelayLimits.EMPTY_PRACTICE_TTL_MS : RelayLimits.EMPTY_PAIR_TTL_MS;
            if (pair.emptySince > 0 && now - pair.emptySince > ttl) {
                synchronized (pair) {
                    if (pair.pc == null && pair.phone == null) {
                        pair.removed = true;
                        byInvite.remove(pair.invite, pair);
                        byCode.remove(pair.code, pair);
                    }
                }
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

    /** 상태 알림은 느린 PC 가 점검 스레드(스케줄러)를 막지 않게 전송 스레드로 보낸다 */
    private void sendPeer(Pair pair) {
        WebSocketSession pc = pair.pc;
        ObjectNode body = json.createObjectNode().put("type", "peer").put("phone", pair.phoneState.wire());
        try {
            sender.execute(() -> sendJson(pc, body));
        } catch (RuntimeException rejected) {
            // 종료 중
        }
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
        Object d = raw.getAttributes().get(DECORATED);
        return d instanceof WebSocketSession s ? s : raw;
    }

    private long practiceCount() {
        return byInvite.values().stream().filter(p -> p.practice).count();
    }

    /** PC 가 화면에 그대로 쓸 수 있으므로 휴대폰이 보낸 상태에서 숫자·참거짓·짧은 문자열만 넘긴다 (중첩 값은 버린다) */
    private ObjectNode scalarsOnly(JsonNode status) {
        ObjectNode out = json.createObjectNode();
        status.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (e.getKey().length() > 32) return;
            if (v.isBoolean()) out.put(e.getKey(), v.asBoolean());
            else if (v.isNumber()) out.set(e.getKey(), v);
            else if (v.isTextual() && v.asText().length() <= 64) out.put(e.getKey(), v.asText());
        });
        return out;
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
