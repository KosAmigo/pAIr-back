package com.pair.domain.scoring.service;

import java.time.Duration;
import java.util.Optional;

import com.pair.domain.scoring.dto.ScoringJobDto;

/**
 * 채점 작업 대기열. 구현은 Redis(스트림)이고, 켜져 있을 때만 쓰인다 (pair.scoring-queue.enabled).
 * 꺼낸 작업은 complete / retryLater / fail 중 하나로 끝내야 한다. 끝내지 않고 작업자가 죽으면 recoverStale 이 되살린다.
 */
public interface ScoringQueueService {

    /** 응시를 대기열에 넣는다. 이미 대기 중이거나 채점 중이면 넣지 않고 false. */
    boolean enqueue(String attemptId);

    /** 다음 작업을 꺼낸다. wait 동안 기다려도 없으면 빈 값. */
    Optional<ScoringJobDto> take(Duration wait);

    /** 채점을 마쳤다. */
    void complete(ScoringJobDto job);

    /** 실패했지만 다시 시도한다. delay 뒤에 다시 대기열에 들어간다. */
    void retryLater(ScoringJobDto job, Duration delay);

    /** 더는 시도하지 않는다. 실패 보관함에 남긴다. */
    void fail(ScoringJobDto job, String error);

    /** 다시 시도할 시각이 된 작업을 대기열로 옮긴다. @return 옮긴 개수 */
    int promoteDue();

    /** 꺼낸 뒤 idle 이상 끝나지 않은 작업을 되살린다(시도 횟수 +1, maxAttempts 를 넘으면 실패 보관). @return 처리한 개수 */
    int recoverStale(Duration idle, int maxAttempts);
}
