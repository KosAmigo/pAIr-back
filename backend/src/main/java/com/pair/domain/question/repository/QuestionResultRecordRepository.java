package com.pair.domain.question.repository;

import com.pair.domain.question.entity.QuestionResultRecord;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionResultRecordRepository extends JpaRepository<QuestionResultRecord, QuestionResultRecord.Key> {

    List<QuestionResultRecord> findByScoringId(Long scoringId);
}
