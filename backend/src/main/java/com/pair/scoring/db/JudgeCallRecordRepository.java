package com.pair.scoring.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeCallRecordRepository extends JpaRepository<JudgeCallRecord, Long> {
    List<JudgeCallRecord> findByScoringId(Long scoringId);
}
