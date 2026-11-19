package com.pair.scoring.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckResultRecordRepository extends JpaRepository<CheckResultRecord, Long> {
    List<CheckResultRecord> findByScoringId(Long scoringId);
}
