package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.service.AttemptRegistry;
import com.pair.global.config.PairProperties;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorConsent;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorConsentRepository;
import com.pair.domain.exam.dto.ExamDtos.ConsentRequest;
import com.pair.domain.exam.dto.ExamDtos.EntryInfo;
import com.pair.domain.exam.dto.ExamDtos.TaskStatus;
import com.pair.domain.task.service.TaskCatalog;
import com.pair.domain.task.entity.TaskRecord;

/**
 * 응시자 입장 흐름: 링크(초대 토큰) → 시험 정보 → 동의·환경 점검 → 대기실 → 시작 → 과제별 제출.
 * 토큰은 해시로 바꿔 찾는다 (DB 에는 해시만 있다).
 * 시작 가능 여부는 서버 시각으로만 판단한다. 응시자 PC 시계가 틀려도 모두 같은 순간에 시작한다.
 */
@Service
public class ExamEntryService {

    private static final int MAX_ENV_CHECK_CHARS = 20_000;

    private final ExamParticipantRepository participants;
    private final ProctorConsentRepository consents;
    private final AttemptSubmitter attempts;
    private final AttemptRegistry registry;
    private final ParticipantProgress progress;
    private final ConnectionGrace grace;
    private final TaskCatalog catalog;
    private final PairProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public ExamEntryService(ExamParticipantRepository participants, ProctorConsentRepository consents,
            AttemptSubmitter attempts, AttemptRegistry registry, ParticipantProgress progress, ConnectionGrace grace, TaskCatalog catalog,
            PairProperties props, ObjectMapper json, Clock clock) {
        this.participants = participants;
        this.consents = consents;
        this.attempts = attempts;
        this.registry = registry;
        this.progress = progress;
        this.grace = grace;
        this.catalog = catalog;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EntryInfo info(String token) {
        return view(find(token), clock.instant());
    }

    @Transactional
    public EntryInfo consent(String token, ConsentRequest req) {
        Instant now = clock.instant();
        ExamParticipant p = find(token);
        requireNotEnded(p.getSession(), now);
        String env;
        try {
            env = json.writeValueAsString(req.envCheck());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("환경 점검 결과를 읽지 못했습니다.");
        }
        if (env.length() > MAX_ENV_CHECK_CHARS) throw new IllegalArgumentException("환경 점검 결과가 너무 깁니다.");
        // 다시 점검하면 최신 결과로 바꾼다
        consents.save(new ProctorConsent(p.getId(), req.consentVersion(), env, now));
        return view(p, now);
    }

    /**
     * 대기실 입장. 시작 전에는 들어와 기다릴 수 있다.
     * 시험을 시작한 뒤 나갔다가 다시 들어오는 것은 마지막 연결 뒤 5분(유예) 안에만 된다. 넘으면 링크가 만료된다.
     */
    @Transactional(noRollbackFor = LinkExpiredException.class)
    public EntryInfo enter(String token) {
        Instant now = clock.instant();
        ExamParticipant p = lock(token);
        requireNotEnded(p.getSession(), now);
        if (progress.of(p).started()) grace.touch(p, now);
        p.markEntered(now);
        return view(p, now);
    }

    /** 시험 시작. 과제마다 응시를 만든다. 두 번 눌러도 응시는 한 번만 만든다 (행 잠금). 이미 시작했다면 유예 안의 재입장으로 본다 */
    @Transactional(noRollbackFor = LinkExpiredException.class)
    public EntryInfo start(String token) {
        Instant now = clock.instant();
        ExamParticipant p = lock(token);
        ExamSession s = p.getSession();
        switch (s.phase(now)) {
            case UPCOMING -> throw new IllegalStateException("아직 시작 시각이 아닙니다.");
            case ENDED -> throw new IllegalStateException("이미 끝난 시험입니다.");
            case CANCELED -> throw new IllegalStateException("취소된 시험입니다.");
            case LIVE -> { }
        }
        ParticipantProgress.Progress pr = progress.of(p);
        if (pr.finished()) throw new IllegalStateException("이미 제출했습니다.");
        if (s.isProctoring() && !consents.existsById(p.getId())) {
            throw new IllegalStateException("감독 동의와 환경 점검을 먼저 마쳐 주세요.");
        }
        if (pr.started()) grace.touch(p, now); // 다시 들어온 경우: 유예 안이면 이어 가고, 넘으면 만료
        else p.markSeen(now);
        p.markEntered(now);
        // 과제마다 응시 행(DB)을 만들고 그 ID 로 작업 파일을 만든다. 이미 있는 과제는 건너뛴다 (대상자 행 잠금으로 한 번만)
        var have = pr.records().stream().map(AttemptRecord::getTaskId).collect(java.util.stream.Collectors.toSet());
        for (Long taskId : s.getTaskIds()) {
            if (have.contains(taskId)) continue;
            TaskRecord t = catalog.require(taskId);
            AttemptRecord r = registry.save(AttemptRecord.live(p.getId(), taskId, props.assistant().model(), now));
            attempts.prepare(r, catalog.definitionCode(t));
        }
        return view(p, now);
    }

    /**
     * 시험 화면이 10초마다 보내는 연결 신호. 유예(5분)를 넘겨 돌아오면 링크를 만료시키고 expired=true 로 알린다.
     * 시작 전·제출 후·끝난 시험의 신호는 무시한다.
     */
    @Transactional
    public Heartbeat heartbeat(String token) {
        Instant now = clock.instant();
        ExamParticipant p = lock(token);
        ParticipantProgress.Progress pr = progress.of(p);
        ExamSession.Phase phase = p.getSession().phase(now);
        if (phase != ExamSession.Phase.LIVE || !pr.started() || pr.finished()) return new Heartbeat(ConnectionGrace.isExpired(p, pr.started(), pr.finished(), now));
        try {
            grace.touch(p, now);
            return new Heartbeat(false);
        } catch (LinkExpiredException e) {
            return new Heartbeat(true); // 만료 기록은 touch 가 남겼고, 예외는 여기서 잡으므로 되돌리지 않는다
        }
    }

    /** expired: 유예를 넘겨 링크가 만료됨 (화면은 안내를 띄운다) */
    public record Heartbeat(boolean expired) {}

    /**
     * 과제 하나 직접 제출. 제출한 과제부터 채점을 시작한다(팀원 1 채점 큐).
     * 모든 과제를 제출하면 대상자도 제출 완료로 본다. 종료 시각이 지나면 스케줄러가 남은 과제를 대신 제출하므로 막는다.
     */
    @Transactional
    public EntryInfo submitTask(String token, String taskId) {
        Instant now = clock.instant();
        ExamParticipant p = lock(token);
        ParticipantProgress.Progress pr = progress.of(p);
        requireSubmittable(p, pr, now);
        String attemptId = pr.attempts().get(taskId);
        if (attemptId == null) throw new NoSuchElementException("이 시험의 과제가 아닙니다.");
        if (!pr.finished()) {
            attempts.submitOne(attemptId);
            registry.markSubmitted(attemptId, now);
        }
        return view(p, now);
    }

    /** 남은 과제를 모두 직접 제출 (시험 끝내기) */
    @Transactional
    public EntryInfo submit(String token) {
        Instant now = clock.instant();
        ExamParticipant p = lock(token);
        ParticipantProgress.Progress pr = progress.of(p);
        requireSubmittable(p, pr, now);
        if (pr.finished()) return view(p, now);
        submitAll(attempts, registry, pr, now);
        return view(p, now);
    }

    /** 남은 과제를 모두 제출한다 (파일, DB). 직접 제출과 종료 시각 자동 제출이 같이 쓴다 */
    static void submitAll(AttemptSubmitter attempts, AttemptRegistry registry, ParticipantProgress.Progress pr, Instant now) {
        var open = pr.records().stream().filter(r -> !r.isSubmitted()).map(r -> String.valueOf(r.getId())).toList();
        attempts.submitAll(open);
        open.forEach(id -> registry.markSubmitted(id, now));
    }

    private static void requireSubmittable(ExamParticipant p, ParticipantProgress.Progress pr, Instant now) {
        if (!pr.started()) throw new IllegalStateException("시작하지 않은 시험입니다.");
        if (!pr.finished() && p.getSession().phase(now) == ExamSession.Phase.ENDED) {
            throw new IllegalStateException("종료 시각이 지나 자동 제출을 기다리는 중입니다.");
        }
    }

    ExamParticipant find(String token) {
        if (!InviteTokens.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.findByInviteTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }

    private ExamParticipant lock(String token) {
        if (!InviteTokens.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.lockByInviteTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }

    private static void requireNotEnded(ExamSession s, Instant now) {
        if (s.phase(now) == ExamSession.Phase.CANCELED) throw new IllegalStateException("취소된 시험입니다.");
        if (s.phase(now) == ExamSession.Phase.ENDED) throw new IllegalStateException("이미 끝난 시험입니다.");
    }

    private EntryInfo view(ExamParticipant p, Instant now) {
        ExamSession s = p.getSession();
        ParticipantProgress.Progress pr = progress.of(p);
        List<String> codes = catalog.codesInOrder(s.getTaskIds());
        List<TaskStatus> tasks = codes.stream()
                .map(code -> new TaskStatus(code, pr.attempts().get(code), pr.submitted().getOrDefault(code, false)))
                .toList();
        boolean linkExpired = s.phase(now) == ExamSession.Phase.LIVE && ConnectionGrace.isExpired(p, pr.started(), pr.finished(), now);
        return new EntryInfo(s.getTitle(), null, p.getName(), p.getEmail(), s.getStartsAt(), s.getEndsAt(), now, s.phase(now), s.isProctoring(),
                consents.existsById(p.getId()), pr.startedAt(), pr.submittedAt(), codes, pr.attempts(), tasks, linkExpired);
    }
}
