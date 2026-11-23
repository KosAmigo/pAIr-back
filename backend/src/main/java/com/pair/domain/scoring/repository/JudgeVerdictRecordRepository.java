package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.JudgeVerdictRecord;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeVerdictRecordRepository extends JpaRepository<JudgeVerdictRecord, Long> {
    List<JudgeVerdictRecord> findByResultId(Long resultId);

    List<JudgeVerdictRecord> findByResultIdIn(Collection<Long> resultIds);
}
