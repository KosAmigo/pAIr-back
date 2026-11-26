package com.pair.domain.exam.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.exam.dto.VisionEventRequest;
import com.pair.domain.exam.dto.VisionEventResponse;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorEventRepository;
import com.pair.global.error.CodedException;

/**
 * 추론 서버가 보낸 이벤트 후보를 proctor_events 에 저장한다 (계약 §3-4). 서명 검증은 컨트롤러 앞단(VisionSignatureService)에서 끝났다.
 * clientId 로 멱등이다: 같은 이벤트가 다시 오면 새로 만들지 않고, 더 긴 durationMs 일 때만 지속시간을 늘린다(줄어들지 않는다).
 * 이벤트에는 사진을 붙이지 않는다. 구간 사진은 VisionPromotionService 가 상시 저장분에서 승격한다.
 * 모르는 유형·받지 않는 유형·출처가 안 맞는 이벤트는 400 INVALID_EVENT 로 거절하며(추론 서버는 재시도하지 않는다) 콘솔에는 닿지 않는다.
 */
@Service
public class VisionEventService {

    /** 대상자 한 명이 가질 수 있는 이벤트 수 (넘으면 새 이벤트를 만들지 않는다) */
    public static final int MAX_EVENTS_PER_PARTICIPANT = 3_000;
    public static final int MAX_DETAIL_CHARS = 4_000;
    private static final int MAX_DURATION_MS = 3_600_000;
    private static final Duration LATE_GRACE = Duration.ofMinutes(30);
    private static final long FUTURE_SKEW_MS = 5_000;
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{8,36}");
    private static final Pattern SESSION_KEY = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final int MAX_RULES_VERSION = 40;

    private static final Set<ProctorEvent.Source> PC = EnumSet.of(ProctorEvent.Source.PC_CAM);
    private static final Set<ProctorEvent.Source> PHONE = EnumSet.of(ProctorEvent.Source.PHONE_CAM);
    /** 서버 추론이 만드는 유형과, 그 유형이 나올 수 있는 출처. SUPERVISION_PAUSED 는 백엔드가 직접 만든다 */
    private static final Map<ProctorEvent.Type, Set<ProctorEvent.Source>> ALLOWED = Map.ofEntries(
            Map.entry(ProctorEvent.Type.NO_FACE, PC), Map.entry(ProctorEvent.Type.MULTI_FACE, PC),
            Map.entry(ProctorEvent.Type.HEAD_TURN, PC), Map.entry(ProctorEvent.Type.HEAD_DOWN, PC),
            Map.entry(ProctorEvent.Type.GAZE_AWAY, PC), Map.entry(ProctorEvent.Type.TORSO_SHIFT, PC),
            Map.entry(ProctorEvent.Type.TORSO_TURN, PC),
            Map.entry(ProctorEvent.Type.HANDS_OUT, PHONE), Map.entry(ProctorEvent.Type.HAND_OUT_ONE, PHONE),
            Map.entry(ProctorEvent.Type.PHONE_MOVED, PHONE),
            Map.entry(ProctorEvent.Type.TABLET, PHONE), Map.entry(ProctorEvent.Type.NOTE, PHONE),
            Map.entry(ProctorEvent.Type.SMARTWATCH, PHONE));

    /** 저장 결과: created 가 true 면 201, 아니면 200 */
    public record Outcome(boolean created, VisionEventResponse body) {}

    private final ExamParticipantRepository participants;
    private final ProctorEventRepository events;
    private final ParticipantProgressService progress;
    private final VisionSessionService vision;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public VisionEventService(ExamParticipantRepository participants, ProctorEventRepository events, ParticipantProgressService progress,
            VisionSessionService vision, ObjectMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.participants = participants;
        this.events = events;
        this.progress = progress;
        this.vision = vision;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public Outcome receive(VisionEventRequest req) {
        Parsed parsed = validate(req);
        try {
            return tx.execute(status -> store(req, parsed));
        } catch (DataIntegrityViolationException raced) {
            return tx.execute(status -> store(req, parsed)); // 같은 clientId 가 동시에 들어왔다: 먼저 저장된 것을 찾는다
        }
    }

    private Outcome store(VisionEventRequest req, Parsed parsed) {
        ExamParticipant p = participants.findByVisionKey(req.sessionKey())
                .orElseThrow(() -> new CodedException(HttpStatus.NOT_FOUND, "UNKNOWN_SESSION_KEY", "모르는 세션 키입니다."));
        ExamSession s = p.getSession();
        Instant now = clock.instant();
        Instant examStarted = vision.isActive(s) && s.getCanceledAt() == null && !now.isAfter(s.getEndsAt().plus(LATE_GRACE))
                ? progress.of(p).startedAt() : null;
        if (examStarted == null) throw new CodedException(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE", "서버 추론 감독이 진행 중인 응시자가 아닙니다.");

        Optional<ProctorEvent> existing = events.findByParticipantIdAndClientId(p.getId(), req.clientId());
        if (existing.isPresent()) {
            ProctorEvent e = existing.get();
            boolean longer = parsed.durationMs() > e.getDurationMs();
            if (longer) e.extendDurationTo(parsed.durationMs());
            return new Outcome(false, new VisionEventResponse(e.getId(), true, longer));
        }
        long startedMs = req.startedAtMs();
        if (startedMs < examStarted.toEpochMilli() || startedMs > now.toEpochMilli() + FUTURE_SKEW_MS) {
            throw invalid("이벤트 시작 시각이 시험 시간 밖입니다.");
        }
        if (events.countByParticipantId(p.getId()) >= MAX_EVENTS_PER_PARTICIPANT) {
            throw new CodedException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_REACHED", "이 응시자의 이벤트 한도를 넘었습니다.");
        }
        ProctorEvent e = new ProctorEvent(p.getId(), parsed.type(), Instant.ofEpochMilli(startedMs), parsed.durationMs(),
                parsed.confidence(), parsed.detail(), now);
        e.assignClientId(req.clientId());
        events.saveAndFlush(e); // 같은 clientId 가 동시에 들어왔다면 여기서 유니크 위반으로 드러난다
        return new Outcome(true, new VisionEventResponse(e.getId(), false, null));
    }

    /** 필드 모양 검사. 틀리면 400 INVALID_EVENT (추론 서버는 재시도하지 않고 버린다) */
    private Parsed validate(VisionEventRequest req) {
        if (req.clientId() == null || !CLIENT_ID.matcher(req.clientId()).matches()) throw invalid("clientId 형식이 올바르지 않습니다.");
        if (req.sessionKey() == null || !SESSION_KEY.matcher(req.sessionKey()).matches()) throw invalid("sessionKey 형식이 올바르지 않습니다.");
        ProctorEvent.Type type = parseType(req.type());
        ProctorEvent.Source source = parseSource(req.source());
        if (!ALLOWED.getOrDefault(type, Set.of()).contains(source)) throw invalid("이 유형과 출처의 조합은 받지 않습니다.");
        if (req.startedAtMs() == null || req.startedAtMs() <= 0) throw invalid("startedAtMs 가 필요합니다.");
        if (req.durationMs() == null || req.durationMs() < 0 || req.durationMs() > MAX_DURATION_MS) throw invalid("durationMs 범위가 올바르지 않습니다.");
        if (req.rulesVersion() == null || req.rulesVersion().isBlank() || req.rulesVersion().length() > MAX_RULES_VERSION) throw invalid("rulesVersion 이 필요합니다.");
        BigDecimal confidence = null;
        if (req.confidence() != null) {
            if (req.confidence().isNaN() || req.confidence() < 0 || req.confidence() > 1) throw invalid("confidence 는 0~1 입니다.");
            confidence = BigDecimal.valueOf(req.confidence()).setScale(3, RoundingMode.HALF_UP);
        }
        return new Parsed(type, req.durationMs(), confidence, detailOf(req));
    }

    /** detail 은 객체이며 직렬화 4,000자 이하. 규칙 버전은 기록용으로 detail 안에 함께 둔다 */
    private String detailOf(VisionEventRequest req) {
        JsonNode in = req.detail();
        if (in != null && !in.isNull() && !in.isObject()) throw invalid("detail 은 객체여야 합니다.");
        try {
            if (in != null && !in.isNull() && json.writeValueAsString(in).length() > MAX_DETAIL_CHARS) throw invalid("detail 이 너무 깁니다.");
            ObjectNode out = in != null && in.isObject() ? ((ObjectNode) in).deepCopy() : json.createObjectNode();
            out.put("rulesVersion", req.rulesVersion());
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw invalid("detail 을 읽지 못했습니다.");
        }
    }

    private static ProctorEvent.Type parseType(String raw) {
        try {
            return raw == null ? null : ProctorEvent.Type.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid("모르는 이벤트 유형입니다.");
        }
    }

    private static ProctorEvent.Source parseSource(String raw) {
        try {
            return raw == null ? null : ProctorEvent.Source.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid("모르는 출처입니다.");
        }
    }

    private static CodedException invalid(String message) {
        return new CodedException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", message);
    }

    private record Parsed(ProctorEvent.Type type, int durationMs, BigDecimal confidence, String detail) {}
}
