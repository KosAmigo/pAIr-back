package com.pair.domain.exam.service;

import com.pair.global.security.InviteTokenService;
import com.pair.infra.storage.SnapshotStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.global.config.ExamProperties;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorEventRepository;
import com.pair.domain.exam.dto.ProctorEventRequest;
import com.pair.domain.exam.dto.ProctorEventResponse;

/**
 * 감독 이벤트 수신과 사진 보관.
 * 응시자 브라우저가 이상 순간에만 이벤트 1건 + 사진 1장을 보낸다 (영상은 받지 않는다).
 * 브라우저 아웃박스가 끊겼다가 나중에 몰아서 보낼 수 있어서, 종료 뒤 잠깐(LATE_GRACE)은 받는다.
 */
@Service
public class ProctorService {

    /** 종료 뒤에도 이 시간까지는 늦게 도착한 이벤트를 받는다 (끊겼던 브라우저가 몰아서 보내는 경우) */
    private static final Duration LATE_GRACE = Duration.ofMinutes(30);
    /** 응시자 PC 시계 오차 허용 범위. 안쪽이면 가장자리로 맞추고 바깥이면 거절 */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    /** 대상자 한 명에게 붙일 수 있는 감독 사진 수 (넘으면 이벤트만 남긴다) */
    private static final int MAX_SNAPSHOTS_PER_PARTICIPANT = 300;
    private static final int MAX_DETAIL_CHARS = 4000;
    private static final int PURGE_BATCH = 500;

    private final ExamParticipantRepository participants;
    private final ProctorEventRepository events;
    private final SnapshotValidationService snapshots;
    private final SnapshotStorage storage;
    private final ObjectMapper json;
    private final ExamProperties props;
    private final Clock clock;
    private final com.pair.domain.attempt.repository.AttemptRepository attempts;
    private final TransactionTemplate tx;
    private final com.pair.domain.exam.repository.ExamSessionRepository sessions;
    private final com.pair.domain.exam.repository.SnapshotRetentionExtensionRepository extensions;
    private final SnapshotRetentionService retention;
    private final com.pair.domain.exam.repository.ProctorEventFrameRepository clipFrames;

    public ProctorService(ExamParticipantRepository participants, ProctorEventRepository events, SnapshotValidationService snapshots,
            SnapshotStorage storage, ObjectMapper json, ExamProperties props, Clock clock,
            com.pair.domain.attempt.repository.AttemptRepository attempts, PlatformTransactionManager txManager,
            com.pair.domain.exam.repository.ExamSessionRepository sessions,
            com.pair.domain.exam.repository.SnapshotRetentionExtensionRepository extensions, SnapshotRetentionService retention,
            com.pair.domain.exam.repository.ProctorEventFrameRepository clipFrames) {
        this.clipFrames = clipFrames;
        this.sessions = sessions;
        this.extensions = extensions;
        this.retention = retention;
        this.attempts = attempts;
        this.tx = new TransactionTemplate(txManager);
        this.participants = participants;
        this.events = events;
        this.snapshots = snapshots;
        this.storage = storage;
        this.json = json;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 이벤트 1건 + 사진 1장 저장. 세 단계로 나눠 DB 연결을 오래 쥐지 않는다 (작은 서버·연결 수가 적은 DB 에서 시험 중 몰려도 버티게).
     * 1) 짧은 트랜잭션: 검증하고 이벤트를 저장해 바로 커밋한다
     * 2) 트랜잭션 밖: 사진을 파일 저장소에 올린다 (키는 회차/이벤트 번호라 이벤트와 사진이 항상 짝이 된다)
     * 3) 짧은 UPDATE: 이벤트에 사진 위치를 연결한다
     * 업로드가 실패하면 예외를 던져 브라우저가 다시 보내게 한다. 다시 온 요청은 같은 clientId 의 이벤트를 찾아
     * 이벤트를 또 만들지 않고 사진만 다시 올려 연결한다. clientId 가 없는 옛 클라이언트는 이벤트도 되돌려 중복을 막는다.
     */
    public ProctorEventResponse record(String token, ProctorEventRequest req) {
        Instant now = clock.instant();
        Stored stored = storeEvent(token, req, now);
        if (stored.jpeg() == null) return stored.view();

        String key = SnapshotStorage.keyOf(stored.sessionId(), stored.eventId());
        try {
            storage.put(key, stored.jpeg());
        } catch (RuntimeException failed) {
            if (stored.created() && req.clientId() == null) {
                tx.executeWithoutResult(status -> events.deleteById(stored.eventId())); // 다시 보내면 중복이 되므로 이벤트도 되돌린다
            }
            throw failed;
        }
        int size = stored.jpeg().length;
        return tx.execute(status -> {
            events.attachSnapshot(stored.eventId(), key, size);
            return ProctorEventResponse.from(events.findById(stored.eventId()).orElseThrow());
        });
    }

    private Stored storeEvent(String token, ProctorEventRequest req, Instant now) {
        try {
            return tx.execute(status -> store(token, req, now));
        } catch (DataIntegrityViolationException dup) {
            if (req.clientId() == null) throw dup;
            return tx.execute(status -> store(token, req, now)); // 같은 이벤트가 동시에 들어옴: 먼저 저장된 것을 찾는다
        }
    }

    /** 1단계: 검증 + 이벤트 저장. 이미 저장된 같은 clientId 이벤트가 있으면 새로 만들지 않는다 */
    private Stored store(String token, ProctorEventRequest req, Instant now) {
        ExamParticipant p = findParticipant(token);
        ExamSession s = p.getSession();
        if (!s.isProctoring()) throw new IllegalStateException("감독을 쓰지 않는 회차입니다.");
        if (req.type() == ProctorEvent.Type.CONNECTION_LOST || req.type() == ProctorEvent.Type.LINK_EXPIRED
                || req.type() == ProctorEvent.Type.LINK_RESTORED) {
            throw new IllegalArgumentException("서버가 직접 남기는 이벤트입니다.");
        }
        if (!attempts.existsByParticipantId(p.getId())) throw new IllegalStateException("시작하지 않은 시험입니다.");

        byte[] jpeg = req.snapshot() == null || req.snapshot().isBlank() ? null : snapshots.decode(req.snapshot());
        if (jpeg != null && !req.type().source().isCamera()) {
            throw new IllegalArgumentException("브라우저 이벤트와 화면 공유 이벤트에는 사진을 붙이지 않습니다.");
        }

        if (req.clientId() != null) {
            var existing = events.findByParticipantIdAndClientId(p.getId(), req.clientId());
            if (existing.isPresent()) {
                ProctorEvent e = existing.get();
                boolean needsPhoto = e.getSnapshotKey() == null && jpeg != null;
                return new Stored(ProctorEventResponse.from(e), e.getId(), s.getId(), needsPhoto ? jpeg : null, false);
            }
        }

        // 종료 뒤에도 잠깐 받는다 (브라우저가 끊겼다 한꺼번에 보낼 수 있다). 이벤트가 일어난 시각이 시험 시간 안이면 늦게 와도 기록한다
        if (now.isAfter(s.getEndsAt().plus(LATE_GRACE))) throw new IllegalStateException("끝난 시험의 감독 이벤트는 받지 않습니다.");
        Instant startedAt = clampToExam(req.startedAt(), s.getStartsAt(), now);

        // 한 사람의 사진이 너무 많이 쌓이면 이벤트는 남기고 사진만 더 받지 않는다 (저장 공간 보호)
        boolean overLimit = jpeg != null
                && events.countByParticipantIdAndSnapshotKeyIsNotNull(p.getId()) >= MAX_SNAPSHOTS_PER_PARTICIPANT;
        if (overLimit) jpeg = null;
        ProctorEvent e = events.save(new ProctorEvent(p.getId(), req.type(), startedAt, req.durationMs(),
                req.confidence(), detail(req), now));
        if (overLimit) e.skipSnapshot(ProctorEvent.SnapshotSkipReason.LIMIT_EXCEEDED); // 감독 화면에 "상한 초과"로 보인다
        e.assignClientId(req.clientId());
        events.flush(); // 같은 clientId 가 동시에 들어왔다면 여기서 유니크 위반으로 드러난다
        return new Stored(ProctorEventResponse.from(e), e.getId(), s.getId(), jpeg, true);
    }

    /**
     * 응시자 PC 시계가 조금 틀려도 이벤트를 버리지 않는다. 시험 시작 전·서버 현재 시각 뒤로 어긋난 시각은 가장자리로 맞추고,
     * 허용 오차(CLOCK_SKEW)를 크게 넘으면 잘못된 값으로 보고 거절한다.
     */
    private static Instant clampToExam(Instant at, Instant startsAt, Instant now) {
        if (at.isBefore(startsAt.minus(CLOCK_SKEW)) || at.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("이벤트 시각이 시험 시간 밖입니다.");
        }
        if (at.isBefore(startsAt)) return startsAt;
        return at.isAfter(now) ? now : at;
    }

    /** 1단계 결과. jpeg 가 null 이면 올릴 사진이 없다 (없거나, 상한을 넘었거나, 이미 올라가 있음) */
    private record Stored(ProctorEventResponse view, Long eventId, Long sessionId, byte[] jpeg, boolean created) {}

    @Transactional(readOnly = true)
    public List<ProctorEventResponse> eventsOf(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        return events.findByParticipantIdOrderByStartedAtAsc(p.getId()).stream().map(ProctorEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ProctorEventResponse> eventsOfSession(Long sessionId) {
        return events.findBySessionId(sessionId).stream().map(ProctorEventResponse::from).toList();
    }

    /** 담당자 판정 (OK/SUSPECT). review 가 null 이면 판정을 지운다 */
    @Transactional
    public ProctorEventResponse review(Long sessionId, Long eventId, ProctorEvent.Review review, String note, Long reviewerId) {
        ProctorEvent e = eventOf(sessionId, eventId);
        e.review(review, note == null || note.isBlank() ? null : note.trim(), reviewerId, clock.instant());
        return ProctorEventResponse.from(e);
    }

    /** 콘솔에서 사진 보기. 보관 기간이 지나 지운 사진은 IllegalStateException */
    @Transactional(readOnly = true)
    public byte[] snapshot(Long sessionId, Long eventId) {
        ProctorEvent e = eventOf(sessionId, eventId);
        if (e.getSnapshotKey() == null) throw new NoSuchElementException("사진이 없는 이벤트입니다.");
        if (e.getSnapshotDeletedAt() != null) throw new IllegalStateException("보관 기간이 지나 삭제된 사진입니다.");
        return storage.get(e.getSnapshotKey()).orElseThrow(() -> new NoSuchElementException("사진 파일이 없습니다."));
    }

    /** 회차 종료 뒤 보관 기간(기본 7일, 연장 반영)이 지난 사진 파일을 지우고 기록만 남긴다. @return 지운 장수 */
    @Transactional
    public int purgeExpiredSnapshots() {
        Instant now = clock.instant();
        Instant baseBefore = now.minus(Duration.ofDays(props.snapshotRetentionDays()));
        java.util.Set<Long> candidates = new java.util.LinkedHashSet<>(events.findSessionIdsWithLiveSnapshots(baseBefore));
        candidates.addAll(clipFrames.allSessionIdsWithLiveFrames()); // 증거 구간 사진도 같은 규칙. 아래에서 회차별 삭제 예정 시각으로 거른다
        List<Long> endedSessions = List.copyOf(candidates);
        if (endedSessions.isEmpty()) return 0;
        int total = 0;
        for (Long sessionId : endedSessions) {
            Instant endsAt = sessions.findById(sessionId).map(ExamSession::getEndsAt).orElse(null);
            if (endsAt == null || retention.expiryOf(endsAt, (long) extensions.findBySessionIdOrderBySeqAsc(sessionId).size()).isAfter(now)) continue;
            List<ProctorEvent> batch;
            do {
                batch = events.findLiveSnapshotsOfSession(sessionId, PageRequest.of(0, PURGE_BATCH));
                for (ProctorEvent e : batch) {
                    storage.delete(e.getSnapshotKey());
                    e.markSnapshotDeleted(now);
                }
                events.flush();
                total += batch.size();
            } while (batch.size() == PURGE_BATCH);
            List<com.pair.domain.exam.entity.ProctorEventFrame> frameBatch;
            do {
                frameBatch = clipFrames.findLiveOfSession(sessionId, PageRequest.of(0, PURGE_BATCH));
                for (com.pair.domain.exam.entity.ProctorEventFrame f : frameBatch) {
                    storage.delete(f.getStorageKey());
                    f.markDeleted(now);
                }
                clipFrames.flush();
                total += frameBatch.size();
            } while (frameBatch.size() == PURGE_BATCH);
        }
        return total;
    }

    /** 다른 회차 번호로는 이벤트를 볼 수도 판정할 수도 없다 */
    private ProctorEvent eventOf(Long sessionId, Long eventId) {
        ProctorEvent e = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("없는 이벤트입니다."));
        ExamParticipant p = participants.findById(e.getParticipantId()).orElseThrow();
        if (!p.getSession().getId().equals(sessionId)) throw new NoSuchElementException("없는 이벤트입니다.");
        return e;
    }

    private ExamParticipant findParticipant(String token) {
        if (!InviteTokenService.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.findByInviteTokenHash(InviteTokenService.hash(token)).orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }

    private String detail(ProctorEventRequest req) {
        if (req.detail() == null || req.detail().isNull()) return null;
        try {
            String s = json.writeValueAsString(req.detail());
            if (s.length() > MAX_DETAIL_CHARS) throw new IllegalArgumentException("이벤트 상세가 너무 깁니다.");
            return s;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트 상세를 읽지 못했습니다.");
        }
    }
}
