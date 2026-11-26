package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.Scoring;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRepository extends JpaRepository<Scoring, Long> {
    /** 응시의 가장 최근에 끝난 채점 */
    Optional<Scoring> findFirstByAttemptIdAndStatusOrderByIdDesc(Long attemptId, String status);

    /** 이 시각 뒤에 시작한 채점 수 (다시 채점 횟수 제한) */
    long countByAttemptIdAndStartedAtAfter(Long attemptId, java.time.Instant after);

    List<Scoring> findByAttemptIdInAndStatus(Collection<Long> attemptIds, String status);
}
