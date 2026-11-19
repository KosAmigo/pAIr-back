package com.pair.scoring.db;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRecordRepository extends JpaRepository<ScoringRecord, Long> {
    /** 응시의 가장 최근에 끝난 채점 */
    Optional<ScoringRecord> findFirstByAttemptIdAndStatusOrderByIdDesc(Long attemptId, String status);
}
