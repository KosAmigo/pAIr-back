package com.pair.domain.exam.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.exam.dto.VisionAdmissionDto;
import com.pair.domain.exam.entity.ProctorStreamPack;
import com.pair.global.config.ExamProperties;
import com.pair.global.config.VisionProperties;
import com.pair.infra.ai.InferenceClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 서버 추론 감독 중계 (/ws/vision, 계약 §3-1). 응시자 PC(웹캠)와 휴대폰이 보낸 JPEG 프레임을 받아
 * ① exam 모드면 채널마다 초당 1장을 상시 저장하고(StreamRecorderService) ② 최신 한 장만 추론 서버로 넘긴다(우편함, 대기열 없음).
 * 입장 점검(check) 모드에서는 저장하지 않고 추론 서버의 판정(불리언·힌트 코드)만 PC 로 전달한다. 영상·사진은 어디로도 돌려주지 않는다.
 * 추론 서버가 죽으면 프레임을 보내지 않고 응시자를 "감독 중단"으로 기록한다(시험은 계속). 사진·키·입장 토큰·짝 코드는 로그에 남기지 않는다.
 * 기존 /ws/proctor 는 그대로이고, 이 경로는 서버 추론이 켜진 회차에서만 열린다(아니면 4403 VISION_DISABLED).
 * 방(room)과 짝 코드는 메모리에만 있다: 서버가 여러 대가 되면 같은 응시자가 같은 서버로 붙어야 한다(지금은 한 대 기준).
 */
@Component
public class VisionRelayService extends AbstractWebSocketHandler implements InferenceClient.Listener {
    private static final Logger log = LoggerFactory.getLogger(VisionRelayService.class);
    private static final Pattern PAIR_CODE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern HINT = Pattern.compile("[A-Z_]{1,32}");
    private static final Pattern ITEM = Pattern.compile("[A-Za-z_]{1,24}");
    private static final String ROLE = "vision.role";
    private static final String ROOM = "vision.room";
    private static final String LIMITER = "vision.limiter";
    private static final String DECORATED = "vision.session";
    private static final String COUNTED = "vision.counted";
    private static final String LAST_ERROR = "vision.lastError";
    private static final String WEBCAM = "webcam";
    private static final String PHONE = "phone";
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private static final int MAX_TEXT_BYTES = 4_096;
    private static final int HEADER_BYTES = 4;
    /** 소켓 하나가 1초에 처리되는 프레임 수 (넘는 것은 조용히 버린다), 1초에 30장을 넘으면 닫는다 */
    private static final int MAX_FRAMES_PER_SEC = 3;
    private static final int MAX_EXCESS_PER_SEC = 27;
    private static final long PHONE_STALE_MS = 15_000;
    private static final long EMPTY_ROOM_TTL_MS = 10 * 60_000;
    private static final long HELLO_TIMEOUT_MS = 10_000;
    private static final long STATUS_EVERY_MS = 30_000;
    private static final long RETRY_START_MS = 5_000;
    private static final long ERROR_NOTICE_MS = 10_000;
    private static final long CHECK_MIN_GAP_MS = 500;
    private static final long LATE_GRACE_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_ROOMS = RelayLimitService.MAX_PAIRS;

    /** 응시자 한 명의 웹캠·휴대폰 방. 소켓 교체·정리는 room 을 잠그고 한다 */
    static final class Room {
        final long sessionId;
        final long participantId;
        final String visionKey;
        final String pairCode;
        final long endsAtMs;
        final LatestFrameMailboxService webcamBox = new LatestFrameMailboxService();
        final LatestFrameMailboxService phoneBox = new LatestFrameMailboxService();
        final AtomicLong webcamSeq = new AtomicLong();
        final AtomicLong phoneSeq = new AtomicLong();
        volatile WebSocketSession webcam;
        volatile WebSocketSession phone;
        volatile boolean exam;
        volatile String phoneState = "disconnected";
        volatile long phoneSeen;
        volatile long lastFrameMs;
        volatile long examSinceMs;
        /** 추론 서버에 세션을 등록했는지 (등록되지 않았으면 프레임을 넘기지 않는다) */
        volatile boolean started;
        volatile boolean recalibrate;
        volatile boolean noCapacity;
        volatile long lastStartTry;
        volatile long lastStatusAt;
        volatile long lastCheckAt;
        volatile long lastRestartAt;
        volatile boolean paused;
        volatile long emptySince;
        boolean removed;

        Room(long sessionId, long participantId, String visionKey, String pairCode, long endsAtMs) {
            this.sessionId = sessionId;
            this.participantId = participantId;
            this.visionKey = visionKey;
            this.pairCode = pairCode;
            this.endsAtMs = endsAtMs;
        }
    }

    private final VisionSessionService sessions;
    private final StreamRecorderService recorder;
    private final InferenceClient inference;
    private final VisionHealthService health;
    private final SupervisionPauseService pauses;
    private final VisionProperties props;
    private final ExamProperties examProps;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Room> byParticipant = new ConcurrentHashMap<>();
    private final Map<String, Room> byCode = new ConcurrentHashMap<>();
    private final Map<String, Room> byKey = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Long> unauthenticated = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private int sweeps;

    public VisionRelayService(VisionSessionService sessions, StreamRecorderService recorder, InferenceClient inference,
            VisionHealthService health, SupervisionPauseService pauses, VisionProperties props, ExamProperties examProps,
            ObjectMapper json, Clock clock) {
        this.sessions = sessions;
        this.recorder = recorder;
        this.inference = inference;
        this.health = health;
        this.pauses = pauses;
        this.props = props;
        this.examProps = examProps;
        this.json = json;
        this.clock = clock;
    }

    @PostConstruct
    void register() {
        inference.setListener(this);
        health.onChange(this::onHealthChanged);
    }

    // ── 연결 ──

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        raw.getAttributes().put(DECORATED, new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT));
        if (!props.enabled()) {
            closeWith(raw, 4403, "VISION_DISABLED", "서버 추론 감독을 쓰지 않습니다.");
            return;
        }
        if (connections.incrementAndGet() > RelayLimitService.MAX_CONNECTIONS) {
            connections.decrementAndGet();
            closeWith(raw, 4429, "RATE_LIMITED", "지금은 연결이 많아 잠시 뒤 다시 시도해 주세요.");
            return;
        }
        raw.getAttributes().put(COUNTED, Boolean.TRUE);
        unauthenticated.put(raw, clock.millis());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        if (message.getPayloadLength() > MAX_TEXT_BYTES) return;
        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (IOException e) {
            return;
        }
        String type = msg.path("type").asText("");
        boolean greeted = raw.getAttributes().get(ROLE) != null;
        switch (type) {
            case "hello" -> {
                if (greeted) closeWith(raw, 4400, "BAD_REQUEST", "hello 는 한 번만 보낼 수 있습니다."); // 소켓당 hello 는 한 번만
                else hello(raw, msg);
            }
            case "ping", "heartbeat" -> {
                if (!greeted) return;
                touch(raw);
                if ("ping".equals(type)) sendJson(decorated(raw), json.createObjectNode().put("type", "pong"));
            }
            default -> { /* 모르는 메시지는 무시한다 */ }
        }
    }

    private void hello(WebSocketSession raw, JsonNode msg) {
        String role = msg.path("role").asText("");
        if (WEBCAM.equals(role)) helloWebcam(raw, msg.path("invite").asText(""));
        else if (PHONE.equals(role)) helloPhone(raw, msg.path("pairCode").asText(""));
        else closeWith(raw, 4400, "BAD_REQUEST", "알 수 없는 역할입니다.");
    }

    private void helloWebcam(WebSocketSession raw, String invite) {
        VisionAdmissionDto admission = sessions.admit(invite);
        switch (admission.result()) {
            case UNAUTHORIZED -> { closeWith(raw, 4401, "UNAUTHORIZED", "입장 링크가 올바르지 않습니다."); return; }
            case DISABLED -> { closeWith(raw, 4403, "VISION_DISABLED", "이 회차는 서버 추론 감독을 쓰지 않습니다."); return; }
            case CONSENT_REQUIRED -> { closeWith(raw, 4403, "CONSENT_REQUIRED", "감독 동의를 다시 받아야 합니다."); return; }
            case OK -> { }
        }
        Room room = byParticipant.get(admission.participantId());
        boolean created = false;
        if (room == null) {
            if (byParticipant.size() >= MAX_ROOMS) {
                closeWith(raw, 4429, "RATE_LIMITED", "지금은 연결이 많아 잠시 뒤 다시 시도해 주세요.");
                return;
            }
            Room fresh = new Room(admission.sessionId(), admission.participantId(), admission.visionKey(), newCode(),
                    admission.endsAt().toEpochMilli());
            fresh.exam = admission.examStarted();
            fresh.examSinceMs = clock.millis();
            room = byParticipant.computeIfAbsent(admission.participantId(), k -> fresh);
            created = room == fresh;
            if (created) {
                byCode.put(room.pairCode, room);
                byKey.put(room.visionKey, room);
            }
        }
        WebSocketSession session = decorated(raw);
        WebSocketSession previous;
        synchronized (room) {
            if (room.removed) { // 정리 직전에 걸렸다. 한 번 다시 시도하게 닫는다
                closeWith(raw, 4500, "INTERNAL", "잠시 뒤 다시 시도해 주세요.");
                return;
            }
            previous = room.webcam;
            room.webcam = session;
            room.emptySince = 0;
        }
        if (previous != null && previous != session) closeWith(previous, 4409, "REPLACED", "다른 화면에서 같은 응시자로 연결했습니다.");
        unauthenticated.remove(raw);
        raw.getAttributes().put(ROLE, WEBCAM);
        raw.getAttributes().put(ROOM, room);
        raw.getAttributes().put(LIMITER, new FrameRateLimitService(MAX_FRAMES_PER_SEC, MAX_EXCESS_PER_SEC));
        allowFrameBuffers(raw);
        sendJson(session, json.createObjectNode().put("type", "paired").put("pairCode", room.pairCode)
                .put("phoneUrl", examProps.entryBaseUrl().replaceAll("/+$", "") + "/proctor/phone?p=" + room.pairCode));
        sendJson(session, ready(room, WEBCAM));
        sendJson(session, peerMessage(room));
        if (created || !room.started) startInference(room);
        log.info("서버 추론: 웹캠 연결 (대상자 {}, {})", room.participantId, room.exam ? "exam" : "check");
    }

    private void helloPhone(WebSocketSession raw, String code) {
        Room room = PAIR_CODE.matcher(code).matches() ? byCode.get(code) : null;
        if (room == null) {
            closeWith(raw, 4401, "UNAUTHORIZED", "연결 정보가 올바르지 않습니다. PC 화면의 QR 을 다시 찍어 주세요.");
            return;
        }
        WebSocketSession session = decorated(raw);
        WebSocketSession previous;
        synchronized (room) {
            if (room.removed) {
                closeWith(raw, 4401, "UNAUTHORIZED", "연결 정보가 올바르지 않습니다. PC 화면의 QR 을 다시 찍어 주세요.");
                return;
            }
            previous = room.phone;
            room.phone = session;
            room.emptySince = 0;
            room.phoneSeen = clock.millis();
            room.phoneState = "connected";
        }
        if (previous != null && previous != session) closeWith(previous, 4409, "REPLACED", "다른 휴대폰이 같은 응시자로 연결했습니다.");
        unauthenticated.remove(raw);
        raw.getAttributes().put(ROLE, PHONE);
        raw.getAttributes().put(ROOM, room);
        raw.getAttributes().put(LIMITER, new FrameRateLimitService(MAX_FRAMES_PER_SEC, MAX_EXCESS_PER_SEC));
        allowFrameBuffers(raw);
        sendJson(session, ready(room, PHONE));
        sendToWebcam(room, peerMessage(room));
        log.info("서버 추론: 휴대폰 연결 (대상자 {})", room.participantId);
    }

    /** 컨테이너 기본(8KB)은 프레임(최대 100KB)보다 작아서, hello 를 통과한 연결에 한해 프레임 한도까지 받게 한다 */
    private void allowFrameBuffers(WebSocketSession raw) {
        if (raw instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session s = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (s != null) s.setMaxBinaryMessageBufferSize(RelayLimitService.MAX_FRAME_BYTES + 1024);
        }
    }

    // ── 프레임 ──

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) {
        Room room = (Room) raw.getAttributes().get(ROOM);
        String role = (String) raw.getAttributes().get(ROLE);
        if (room == null || role == null) return;
        ByteBuffer buf = message.getPayload();
        int size = buf.remaining();
        FrameRateLimitService limiter = (FrameRateLimitService) raw.getAttributes().get(LIMITER);
        long now = clock.millis();
        if (limiter != null && !limiter.allow(now)) {
            if (limiter.abusive()) closeWith(raw, 4429, "RATE_LIMITED", "프레임을 너무 빨리 보내고 있습니다.");
            return; // 초당 처리 한도를 넘은 프레임은 조용히 버린다
        }
        if (size > RelayLimitService.MAX_FRAME_BYTES) {
            frameError(raw, "FRAME_TOO_LARGE", now);
            return;
        }
        if (size < HEADER_BYTES + 3) {
            frameError(raw, "BAD_FRAME", now);
            return;
        }
        byte[] frame = new byte[size];
        buf.get(frame);
        boolean jpeg = (frame[HEADER_BYTES] & 0xFF) == 0xFF && (frame[HEADER_BYTES + 1] & 0xFF) == 0xD8 && (frame[HEADER_BYTES + 2] & 0xFF) == 0xFF;
        if (!jpeg) {
            frameError(raw, "BAD_FRAME", now);
            return;
        }
        byte[] image = Arrays.copyOfRange(frame, HEADER_BYTES, size);
        boolean phone = PHONE.equals(role);
        room.lastFrameMs = now;
        if (phone) touchPhone(room, now);
        if (room.exam) {
            recorder.offer(room.sessionId, room.participantId, phone ? ProctorStreamPack.Channel.PHONE : ProctorStreamPack.Channel.WEBCAM, now, image);
        }
        forward(room, phone, now, image);
    }

    /** 최신 한 장만 추론 서버로 넘긴다 (우편함: 이전 프레임이 안 나갔으면 덮어써서 버린다). 맨 앞 8바이트는 받은 시각 */
    private void forward(Room room, boolean phone, long recvMs, byte[] image) {
        if (!room.started || !health.isUp()) return;
        LatestFrameMailboxService box = phone ? room.phoneBox : room.webcamBox;
        byte[] payload = ByteBuffer.allocate(8 + image.length).putLong(recvMs).put(image).array();
        if (box.offer(payload)) {
            try {
                worker.execute(() -> drain(room, phone));
            } catch (RuntimeException rejected) {
                box.reset(); // 종료 중이라 못 보냈다. 전송 상태를 되돌려 우편함이 막히지 않게 한다
            }
        }
    }

    private void drain(Room room, boolean phone) {
        LatestFrameMailboxService box = phone ? room.phoneBox : room.webcamBox;
        byte[] payload;
        while ((payload = box.take()) != null) {
            if (!room.started || !health.isUp()) continue;
            ByteBuffer in = ByteBuffer.wrap(payload);
            long recvMs = in.getLong();
            byte[] image = new byte[in.remaining()];
            in.get(image);
            try {
                inference.sendFrame(room.visionKey, phone ? InferenceClient.Channel.PHONE : InferenceClient.Channel.WEBCAM,
                        (phone ? room.phoneSeq : room.webcamSeq).incrementAndGet(), recvMs, image);
                health.recordSend(true);
            } catch (RuntimeException e) {
                health.recordSend(false);
                log.debug("추론 서버로 프레임 전달 실패: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void frameError(WebSocketSession raw, String code, long now) {
        Object last = raw.getAttributes().get(LAST_ERROR);
        if (last instanceof Long at && now - at < ERROR_NOTICE_MS) return; // 10초에 한 번만 알린다
        raw.getAttributes().put(LAST_ERROR, now);
        sendJson(decorated(raw), json.createObjectNode().put("type", "error").put("code", code).put("message", "이 프레임은 받지 않았습니다."));
    }

    // ── 추론 서버가 보내는 것 (InferenceClient.Listener) ──

    @Override
    public void onState(String sessionKey) {
        health.recordState(sessionKey, clock.millis());
    }

    @Override
    public void onCheck(String sessionKey, JsonNode body) {
        Room room = byKey.get(sessionKey);
        if (room == null || room.exam) return; // 입장 점검 중일 때만 PC 로 돌려준다
        long now = clock.millis();
        if (now - room.lastCheckAt < CHECK_MIN_GAP_MS) return; // 최대 초당 2번
        room.lastCheckAt = now;
        sendToWebcam(room, checkMessage(body));
    }

    @Override
    public void onError(String sessionKey, String code) {
        Room room = byKey.get(sessionKey);
        if (room == null || !"UNKNOWN_SESSION".equals(code)) return;
        long now = clock.millis();
        if (now - room.lastRestartAt < ERROR_NOTICE_MS) return;
        room.lastRestartAt = now;
        room.recalibrate = true;
        room.started = false; // 다음 점검 주기에 보정부터 다시 등록한다
    }

    /** 추론 서버 판정에서 불리언 항목·힌트 코드·숫자만 골라 PC 로 보낸다 (그 밖의 내용은 전달하지 않는다) */
    private ObjectNode checkMessage(JsonNode body) {
        ObjectNode out = json.createObjectNode().put("type", "check");
        ObjectNode items = out.putObject("items");
        JsonNode in = body.path("items");
        if (in.isObject()) {
            int n = 0;
            for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) in::fields) {
                if (n >= 12) break;
                if (e.getValue().isBoolean() && ITEM.matcher(e.getKey()).matches()) {
                    items.put(e.getKey(), e.getValue().asBoolean());
                    n++;
                }
            }
        }
        ArrayNode hints = out.putArray("hints");
        JsonNode h = body.path("hints");
        if (h.isArray()) {
            int n = 0;
            for (JsonNode x : h) {
                if (n >= 10) break;
                if (x.isTextual() && HINT.matcher(x.asText()).matches()) {
                    hints.add(x.asText());
                    n++;
                }
            }
        }
        out.put("holdMs", Math.max(0, body.path("holdMs").asInt(0)));
        out.put("passed", body.path("passed").asBoolean(false));
        return out;
    }

    // ── 추론 서버 등록·상태 ──

    /** 추론 서버에 이 응시자의 세션을 등록한다 (비동기). 자리가 없으면 감독 중단(NO_CAPACITY) 후보가 된다 */
    private void startInference(Room room) {
        room.lastStartTry = clock.millis();
        boolean recalibrate = room.recalibrate;
        boolean exam = room.exam;
        try {
            worker.execute(() -> {
                try {
                    InferenceClient.StartResult result = inference.start(room.visionKey, exam ? InferenceClient.Mode.EXAM : InferenceClient.Mode.CHECK,
                            clock.millis(), recalibrate);
                    room.noCapacity = result != InferenceClient.StartResult.ACCEPTED;
                    room.started = result == InferenceClient.StartResult.ACCEPTED;
                    if (room.started) room.recalibrate = false;
                    health.recordSend(true);
                } catch (RuntimeException e) {
                    health.recordSend(false);
                    log.debug("추론 서버 세션 등록 실패: {}", e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException rejected) {
            // 종료 중이다
        }
    }

    private void onHealthChanged() {
        boolean up = health.isUp();
        for (Room room : byParticipant.values()) {
            if (up) { // 추론 서버가 돌아왔다: 세션을 다시 등록하고 보정부터 다시 한다 (상시 저장은 영향이 없다)
                room.started = false;
                room.recalibrate = true;
            }
            sendStatus(room);
        }
    }

    // ── 점검 주기 ──

    @Scheduled(fixedDelay = 2_000)
    void sweep() {
        long now = clock.millis();
        unauthenticated.forEach((session, since) -> {
            if (now - since > HELLO_TIMEOUT_MS) {
                unauthenticated.remove(session);
                closeWith(session, 4400, "BAD_REQUEST", "hello 를 보내지 않았습니다.");
            }
        });
        for (Room room : byParticipant.values()) {
            try {
                sweepRoom(room, now);
            } catch (RuntimeException e) {
                log.warn("서버 추론 방 점검 실패 (대상자 {}): {}", room.participantId, e.getClass().getSimpleName());
            }
        }
        if (++sweeps % 3 == 0) pauses.refresh(); // 진행 중인 감독 중단의 지속 시간을 6초마다 갱신
    }

    private void sweepRoom(Room room, long now) {
        if (now > room.endsAtMs + LATE_GRACE_MS) { // 회차 종료 30분 뒤
            closeRoom(room, 4401, "UNAUTHORIZED", "시험이 끝났습니다.");
            return;
        }
        boolean empty = room.webcam == null && room.phone == null;
        if (empty) {
            if (room.emptySince == 0) room.emptySince = now;
            if (now - room.emptySince > EMPTY_ROOM_TTL_MS) removeRoom(room);
            pauses.close(room.participantId);
            return;
        }
        if (room.phone != null && "connected".equals(room.phoneState) && now - room.phoneSeen > PHONE_STALE_MS) {
            room.phoneState = "stale";
            sendToWebcam(room, peerMessage(room));
        }
        if (room.webcam == null) {
            pauses.close(room.participantId);
            return;
        }
        if (!room.exam && sessions.examStarted(room.participantId)) switchToExam(room, now);
        if (!room.started && health.isUp() && now - room.lastStartTry > RETRY_START_MS) startInference(room);
        evaluatePause(room, now);
        if (now - room.lastStatusAt > STATUS_EVERY_MS) sendStatus(room);
    }

    /** 시험 시작(/start)으로 입장 점검 모드가 시험 모드로 바뀐다. 소켓은 그대로 두고 알려 준다 */
    private void switchToExam(Room room, long now) {
        room.exam = true;
        room.examSinceMs = now;
        sendToWebcam(room, json.createObjectNode().put("type", "mode").put("mode", "exam"));
        try {
            worker.execute(() -> {
                try {
                    if (!inference.changeMode(room.visionKey, now)) {
                        room.started = false; // 추론 서버가 모르는 키: 처음부터 다시 등록한다
                        room.recalibrate = true;
                    }
                } catch (RuntimeException e) {
                    health.recordSend(false);
                }
            });
        } catch (RuntimeException rejected) {
            // 종료 중이다
        }
    }

    /** 시험 중인 응시자가 추론 서버 문제로 감독받지 못하는 동안을 "감독 중단"으로 기록한다 */
    private void evaluatePause(Room room, long now) {
        if (!room.exam) return;
        String reason = null;
        if (!health.isUp()) reason = "VISION_DOWN";
        else if (room.noCapacity) reason = "NO_CAPACITY";
        else if (room.started && now - room.lastFrameMs < 5_000
                && now - health.lastState(room.visionKey, room.examSinceMs) > props.noStateMs()) reason = "NO_STATE";
        boolean shouldPause = reason != null;
        if (shouldPause && !room.paused) {
            room.paused = true;
            pauses.open(room.participantId, reason);
            sendStatus(room);
        } else if (!shouldPause && room.paused) {
            room.paused = false;
            pauses.close(room.participantId);
            sendStatus(room);
        }
    }

    private void closeRoom(Room room, int code, String errCode, String message) {
        WebSocketSession webcam = room.webcam;
        WebSocketSession phone = room.phone;
        if (webcam != null) closeWith(webcam, code, errCode, message);
        if (phone != null) closeWith(phone, code, errCode, message);
        removeRoom(room);
    }

    private void removeRoom(Room room) {
        synchronized (room) {
            if (room.removed) return;
            room.removed = true;
        }
        byParticipant.remove(room.participantId, room);
        byCode.remove(room.pairCode, room);
        byKey.remove(room.visionKey, room);
        pauses.close(room.participantId);
        health.forget(room.visionKey);
        recorder.close(room.participantId);
        try {
            worker.execute(() -> {
                try {
                    inference.end(room.visionKey);
                } catch (RuntimeException e) {
                    log.debug("추론 서버 세션 종료 실패: {}", e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException rejected) {
            // 종료 중이다
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        unauthenticated.remove(raw);
        if (raw.getAttributes().remove(COUNTED) != null) connections.decrementAndGet();
        Room room = (Room) raw.getAttributes().get(ROOM);
        if (room == null) return;
        WebSocketSession mine = decorated(raw);
        String role = (String) raw.getAttributes().get(ROLE);
        boolean phoneLeft = false;
        synchronized (room) {
            if (PHONE.equals(role) && room.phone == mine) {
                room.phone = null;
                room.phoneState = "disconnected";
                phoneLeft = true;
            } else if (WEBCAM.equals(role) && room.webcam == mine) {
                room.webcam = null;
            }
            if (room.webcam == null && room.phone == null) room.emptySince = clock.millis();
        }
        if (phoneLeft) sendToWebcam(room, peerMessage(room));
        try {
            worker.execute(() -> recorder.flush(room.participantId)); // 끊긴 동안 모인 사진을 먼저 올려 둔다
        } catch (RuntimeException rejected) {
            // 종료 중이다
        }
    }

    // ── 메시지 ──

    private ObjectNode ready(Room room, String role) {
        ObjectNode rate = json.createObjectNode().put("fps", props.rateFps());
        if (WEBCAM.equals(role)) rate.put("maxEdge", props.webcamMaxEdge()).put("quality", props.webcamQuality());
        else rate.put("maxEdge", props.phoneMaxEdge());
        ObjectNode out = json.createObjectNode().put("type", "ready").put("mode", room.exam ? "exam" : "check")
                .put("vision", health.isUp() ? "up" : "down");
        out.set("rate", rate);
        out.put("serverTimeMs", clock.millis());
        return out;
    }

    private ObjectNode peerMessage(Room room) {
        return json.createObjectNode().put("type", "peer").put("phone", room.phoneState);
    }

    private void sendStatus(Room room) {
        room.lastStatusAt = clock.millis();
        sendToWebcam(room, json.createObjectNode().put("type", "status")
                .put("vision", health.isUp() && !room.paused ? "up" : "down").put("phone", room.phoneState));
    }

    private void sendToWebcam(Room room, ObjectNode body) {
        sendJson(room.webcam, body);
    }

    private void touch(WebSocketSession raw) {
        Room room = (Room) raw.getAttributes().get(ROOM);
        if (room != null && PHONE.equals(raw.getAttributes().get(ROLE))) touchPhone(room, clock.millis());
    }

    private void touchPhone(Room room, long now) {
        room.phoneSeen = now;
        if (!"connected".equals(room.phoneState) && room.phone != null) {
            room.phoneState = "connected";
            sendToWebcam(room, peerMessage(room));
        }
    }

    private void sendJson(WebSocketSession to, ObjectNode body) {
        if (to == null || !to.isOpen()) return;
        try {
            to.sendMessage(new TextMessage(json.writeValueAsString(body)));
        } catch (IOException | RuntimeException e) {
            log.debug("서버 추론 메시지 전송 실패: {}", e.getClass().getSimpleName());
        }
    }

    /** 오류 메시지를 보낸 뒤 약속한 닫기 코드로 닫는다 */
    private void closeWith(WebSocketSession s, int code, String errCode, String message) {
        sendJson(s, json.createObjectNode().put("type", "error").put("code", errCode).put("message", message));
        try {
            s.close(new CloseStatus(code, errCode));
        } catch (IOException | RuntimeException ignored) {
            // 이미 닫혔다
        }
    }

    private WebSocketSession decorated(WebSocketSession raw) {
        Object d = raw.getAttributes().get(DECORATED);
        return d instanceof WebSocketSession s ? s : raw;
    }

    private String newCode() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
