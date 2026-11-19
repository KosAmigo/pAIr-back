package com.pair.scoring.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeVerdictRecordRepository extends JpaRepository<JudgeVerdictRecord, Long> {
    List<JudgeVerdictRecord> findByResultId(Long resultId);
}
