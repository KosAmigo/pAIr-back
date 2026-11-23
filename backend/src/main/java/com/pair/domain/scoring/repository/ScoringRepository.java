package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.Scoring;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRepository extends JpaRepository<Scoring, Long> {
    /** 응시의 가장 최근에 끝난 채점 */
    Optional<Scoring> findFirstByAttemptIdAndStatusOrderByIdDesc(Long attemptId, String status);
}
