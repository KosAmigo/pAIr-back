package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ExamSessionRepository;

/**
 * 종료 시각이 지난 회차를 닫는다: 시작했지만 아직 제출하지 않은 과제를 전원 자동 제출.
 * 시작하지 않은 대상자는 미응시로 남긴다.
 * 회차마다 트랜잭션을 따로 잡아서, 한 회차가 실패해도 다른 회차는 닫힌다.
 * 응시를 채점으로 넘기지 못한 건이 있으면 회차를 닫지 않고 간격을 늘려 가며(2초, 4초, ... 최대 30초) 다시 시도한다.
 * MAX_FAILED_ROUNDS 번 실패하면 같은 응시를 계속 두드리지 않도록 포기하고 닫으며, 오류 로그로 남긴다.
 * 재시도 상태는 서버 메모리에만 둔다 (서버를 다시 켜면 처음부터 다시 센다).
 */
@Service
public class AutoSubmitService {
    private static final Logger log = LoggerFactory.getLogger(AutoSubmitService.class);

    /** 응시를 채점으로 넘기지 못한 채 회차를 다시 시도하는 최대 횟수 (이 횟수째 실패하면 포기하고 닫는다) */
    public static final int MAX_FAILED_ROUNDS = 5;
    static final Duration BASE_BACKOFF = Duration.ofSeconds(2);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private record Retry(int failedRounds, Instant nextAt) {
    }

    /** 복구 결과: pending = 채점 단계에 못 간 응시 수, failed = 그중 이번에도 넘기지 못한 수 */
    public record RescueResult(int pending, int failed) {
    }

    /** 마무리에 실패해 다시 시도를 기다리는 회차 */
    private final Map<Long, Retry> retries = new ConcurrentHashMap<>();

    private final ExamSessionRepository sessions;
    private final ExamParticipantRepository participants;
    private final AttemptSubmitter attempts;
    private final com.pair.domain.attempt.service.AttemptRegistry registry;
    private final ParticipantProgress progress;
    private final Clock clock;

    public AutoSubmitService(ExamSessionRepository sessions, ExamParticipantRepository participants,
            AttemptSubmitter attempts, com.pair.domain.attempt.service.AttemptRegistry registry, ParticipantProgress progress, Clock clock) {
        this.sessions = sessions;
        this.participants = participants;
        this.attempts = attempts;
        this.registry = registry;
        this.progress = progress;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> dueSessionIds() {
        Instant now = clock.instant();
        return sessions.findByEndsAtLessThanEqualAndClosedAtIsNullAndCanceledAtIsNull(now).stream()
                .map(ExamSession::getId)
                .filter(id -> {
                    Retry r = retries.get(id);
                    return r == null || !now.isBefore(r.nextAt());
                })
                .toList();
    }

    /** @return 자동 제출한 응시자 수 */
    @Transactional
    public int close(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = sessions.findById(sessionId).orElse(null);
        if (s == null || s.getClosedAt() != null || s.phase(now) != ExamSession.Phase.ENDED) return 0;

        // 응시자가 같은 순간 직접 제출하는 것과 겹치지 않도록 잠근다
        List<ExamParticipant> ps = participants.lockBySessionId(sessionId);
        int submitted = 0;
        var states = progress.of(ps);
        List<String> toFinish = new ArrayList<>();
        for (ExamParticipant p : ps) {
            ParticipantProgress.Progress pr = states.get(p.getId());
            if (!pr.started()) continue;
            if (!pr.finished()) {
                ExamEntryService.submitAll(attempts, registry, pr, now);
                submitted++;
            }
            // 직접 제출했더라도 후속 질문 단계에서 시간이 끝났으면 채점이 시작되지 않는다. 빈 답으로 마무리해 채점으로 넘긴다
            pr.records().forEach(r -> toFinish.add(String.valueOf(r.getId())));
        }
        int failed = attempts.finishAll(toFinish);
        if (failed > 0) {
            int rounds = retries.containsKey(sessionId) ? retries.get(sessionId).failedRounds() + 1 : 1;
            if (rounds < MAX_FAILED_ROUNDS) {
                Duration wait = backoff(rounds);
                retries.put(sessionId, new Retry(rounds, now.plus(wait)));
                log.warn("회차 {} 종료: 응시 {}건을 채점으로 넘기지 못했습니다. {}초 뒤 다시 시도합니다 ({}/{})",
                        sessionId, failed, wait.toSeconds(), rounds, MAX_FAILED_ROUNDS);
                return submitted; // 닫지 않는다
            }
            log.error("회차 {} 종료: 응시 {}건을 {}번 시도하고도 채점으로 넘기지 못해 포기하고 닫습니다. 직접 확인이 필요합니다",
                    sessionId, failed, MAX_FAILED_ROUNDS);
        }
        retries.remove(sessionId);
        s.close(now);
        long lagMs = Duration.between(s.getEndsAt(), now).toMillis();
        log.info("회차 {} 종료: 자동 제출 {}명, 종료 시각 대비 {}ms 뒤 처리", sessionId, submitted, lagMs);
        return submitted;
    }

    /**
     * 종료된 회차에서 채점 단계에 가지 못한 응시(풀이 중이거나 후속 질문 단계)를 다시 채점으로 넘긴다.
     * 자동 제출이 포기하고 닫은 응시를 담당자가 콘솔에서 되살릴 때 쓴다. 이미 채점 단계인 응시는 건드리지 않아 여러 번 불러도 안전하다.
     * 모두 넘겼는데 아직 닫히지 않은 회차면 이때 닫는다.
     */
    @Transactional
    public RescueResult rescue(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = sessions.findById(sessionId).orElseThrow(() -> new NoSuchElementException("회차를 찾을 수 없습니다."));
        if (s.getCanceledAt() != null || s.phase(now) != ExamSession.Phase.ENDED) {
            throw new IllegalStateException("종료된 회차에서만 멈춘 응시를 다시 넘길 수 있습니다.");
        }
        List<ExamParticipant> ps = participants.lockBySessionId(sessionId);
        List<String> pending = new ArrayList<>();
        progress.of(ps).values().stream()
                .filter(ParticipantProgress.Progress::started)
                .flatMap(pr -> pr.records().stream())
                .filter(r -> r.getStatus() == Attempt.Status.SOLVING || r.getStatus() == Attempt.Status.FOLLOWUP)
                .forEach(r -> pending.add(String.valueOf(r.getId())));
        int failed = attempts.finishAll(pending);
        retries.remove(sessionId);
        if (failed == 0 && s.getClosedAt() == null) s.close(now);
        log.info("회차 {} 복구: 멈춘 응시 {}건 중 {}건을 넘기지 못했습니다", sessionId, pending.size(), failed);
        return new RescueResult(pending.size(), failed);
    }

    /** 실패 횟수에 따른 대기 시간: 2초, 4초, 8초, ... 최대 30초 */
    private static Duration backoff(int failedRounds) {
        long seconds = BASE_BACKOFF.toSeconds() << Math.min(failedRounds - 1, 10);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }
}
