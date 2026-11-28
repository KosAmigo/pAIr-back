package com.pair.domain.scoring.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.scoring.repository.ScoringResultRepository;

/**
 * 서버를 켤 때 채점이 끊긴 응시(SCORING 에 남은 응시)를 다시 채점에 맡긴다.
 * 채점 큐가 꺼져 있으면 채점은 서버 안의 스레드 풀에서 돌아서, 서버가 다시 시작되면 하던 작업과 기다리던 작업이 사라진다.
 * 채점 큐를 켜기 전에 SCORING 이던 응시도 Redis 에 없으므로 여기서 큐에 넣는다(이미 큐에 있으면 enqueue 가 중복을 막는다).
 * 끊긴 채점 기록(RUNNING)은 ERROR 로 닫고, 24시간 채점 한도를 넘은 응시는 다시 맡기지 않고 ERROR 로 둔다(재시작이 반복돼도 비용이 늘지 않게).
 * 서버가 하나라는 전제다. 두 서버가 겹쳐 도는 배포(블루그린)를 하면 이 복구를 다시 검토해야 한다.
 */
@Service
public class ScoringRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ScoringRecoveryService.class);
    private static final Duration WINDOW = Duration.ofDays(1);
    static final String INTERRUPTED = "서버가 다시 시작돼 채점이 중단됐습니다";

    private final AttemptRepository attempts;
    private final ScoringRepository scorings;
    private final ScoringResultRepository records;
    private final ScoringService scoring;
    private final Clock clock;
    private final int maxPerDay;
    private final boolean enabled;

    public ScoringRecoveryService(AttemptRepository attempts, ScoringRepository scorings, ScoringResultRepository records,
            ScoringService scoring, Clock clock,
            @Value("${pair.rescore.max-per-day:10}") int maxPerDay,
            @Value("${pair.scoring.recover-on-startup:true}") boolean enabled) {
        this.attempts = attempts;
        this.scorings = scorings;
        this.records = records;
        this.scoring = scoring;
        this.clock = clock;
        this.maxPerDay = maxPerDay;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        try {
            recover();
        } catch (RuntimeException e) {
            // 복구를 못 해도 서버는 떠야 한다. 남은 응시는 관리자가 다시 채점할 수 있다
            log.error("채점이 끊긴 응시를 복구하지 못했습니다", e);
        }
    }

    /** @return 다시 채점에 맡긴 응시 수 */
    public int recover() {
        List<Long> ids = attempts.findIdsByStatus(AttemptStateDto.Status.SCORING);
        if (ids.isEmpty()) return 0;
        for (Scoring s : scorings.findByAttemptIdInAndStatus(ids, "RUNNING")) {
            records.fail(s.getId(), INTERRUPTED);
        }
        int resumed = 0;
        for (Long id : ids) {
            if (scorings.countByAttemptIdAndStartedAtAfter(id, clock.instant().minus(WINDOW)) >= maxPerDay) {
                scoring.markFailed(String.valueOf(id),
                        new IllegalStateException(INTERRUPTED + " (24시간 채점 한도를 넘어 자동으로 다시 채점하지 않습니다)"));
                log.warn("채점 한도를 넘어 ERROR 로 둡니다: attemptId={}", id);
                continue;
            }
            scoring.scoreAsync(String.valueOf(id));
            resumed++;
        }
        log.info("채점이 끊긴 응시 {}건 중 {}건을 다시 채점에 맡겼습니다: {}", ids.size(), resumed, ids);
        return resumed;
    }
}
