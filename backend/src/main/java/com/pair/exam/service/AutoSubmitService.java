package com.pair.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.repository.ExamSessionRepository;

/**
 * 종료 시각이 지난 회차를 닫는다: 시작했지만 아직 제출하지 않은 과제를 전원 자동 제출.
 * 시작하지 않은 대상자는 미응시로 남긴다.
 * 회차마다 트랜잭션을 따로 잡아서, 한 회차가 실패해도 다른 회차는 닫힌다.
 */
@Service
public class AutoSubmitService {
    private static final Logger log = LoggerFactory.getLogger(AutoSubmitService.class);

    private final ExamSessionRepository sessions;
    private final ExamParticipantRepository participants;
    private final AttemptSubmitter attempts;
    private final com.pair.attempt.AttemptRegistry registry;
    private final ParticipantProgress progress;
    private final Clock clock;

    public AutoSubmitService(ExamSessionRepository sessions, ExamParticipantRepository participants,
            AttemptSubmitter attempts, com.pair.attempt.AttemptRegistry registry, ParticipantProgress progress, Clock clock) {
        this.sessions = sessions;
        this.participants = participants;
        this.attempts = attempts;
        this.registry = registry;
        this.progress = progress;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> dueSessionIds() {
        return sessions.findByEndsAtLessThanEqualAndClosedAtIsNullAndCanceledAtIsNull(clock.instant()).stream().map(ExamSession::getId).toList();
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
        if (failed > 0) log.error("회차 {} 종료: 응시 {}건을 채점으로 넘기지 못했습니다", sessionId, failed);
        s.close(now);
        long lagMs = Duration.between(s.getEndsAt(), now).toMillis();
        log.info("회차 {} 종료: 자동 제출 {}명, 종료 시각 대비 {}ms 뒤 처리", sessionId, submitted, lagMs);
        return submitted;
    }
}
