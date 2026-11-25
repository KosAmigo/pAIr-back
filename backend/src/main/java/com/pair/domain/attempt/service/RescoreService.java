package com.pair.domain.attempt.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.exception.TooManyRescoresException;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.scoring.service.ScoringService;

/**
 * 다시 채점. 채점은 AI 비용이 들므로 채점에 실패한 응시(ERROR)와 옛 방식(버전 2 미만) 리포트만 허용하고,
 * 응시 하나를 24시간에 maxPerDay 번까지만 채점한다(첫 채점과 자동 재시도 포함).
 */
@Service
public class RescoreService {

    private static final Duration WINDOW = Duration.ofDays(1);
    /** 지금 방식의 리포트 버전. 이보다 낮으면 옛 방식이라 새 기준으로 다시 채점할 수 있다 */
    private static final int CURRENT_REPORT_VERSION = 2;

    private final AttemptStateRepository store;
    private final AttemptRepository records;
    private final ScoringRepository scorings;
    private final ScoringService scoring;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxPerDay;

    public RescoreService(AttemptStateRepository store, AttemptRepository records, ScoringRepository scorings,
            ScoringService scoring, TransactionTemplate transactions, Clock clock,
            @Value("${pair.rescore.max-per-day:10}") int maxPerDay) {
        this.store = store;
        this.records = records;
        this.scorings = scorings;
        this.scoring = scoring;
        this.transactions = transactions;
        this.clock = clock;
        this.maxPerDay = maxPerDay;
    }

    public AttemptStateDto rescore(String id) {
        // 채점은 다른 스레드가 DB 를 다시 읽어 시작하므로, 상태를 바꾼 트랜잭션이 끝난 뒤에 맡긴다
        transactions.executeWithoutResult(tx -> begin(id));
        scoring.scoreAsync(id);
        return store.load(id);
    }

    private void begin(String id) {
        AttemptStateDto a = store.load(id);
        if (a.followupsDoneAt() == null) throw new IllegalStateException("후속 질문까지 끝난 응시만 다시 채점할 수 있습니다.");
        if (a.status() == AttemptStateDto.Status.SCORING) throw new IllegalStateException("이미 채점 중입니다.");
        if (a.status() != AttemptStateDto.Status.ERROR && !hasOldReport(a)) {
            throw new IllegalStateException("채점에 실패했거나 이전 방식으로 채점한 응시만 다시 채점할 수 있습니다.");
        }
        long attemptId = Long.parseLong(id);
        if (scorings.countByAttemptIdAndStartedAtAfter(attemptId, clock.instant().minus(WINDOW)) >= maxPerDay) {
            throw new TooManyRescoresException("이 응시는 24시간 동안 채점할 수 있는 횟수(" + maxPerDay + "번)를 넘었습니다. 나중에 다시 시도해 주세요.");
        }
        // 동시에 여러 번 눌러도 상태를 바꾼 요청 하나만 채점을 시작한다
        if (records.startRescoring(attemptId, a.status()) == 0) throw new IllegalStateException("이미 채점 중입니다.");
    }

    private boolean hasOldReport(AttemptStateDto a) {
        if (a.status() != AttemptStateDto.Status.DONE) return false;
        JsonNode report = store.readReport(a.id());
        return report != null && report.path("version").asInt(1) < CURRENT_REPORT_VERSION;
    }
}
