package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.CheckResultRecord;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckResultRecordRepository extends JpaRepository<CheckResultRecord, Long> {
    List<CheckResultRecord> findByScoringId(Long scoringId);
}
