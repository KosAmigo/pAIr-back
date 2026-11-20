package com.pair.question;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionResultRecordRepository extends JpaRepository<QuestionResultRecord, QuestionResultRecord.Key> {

    List<QuestionResultRecord> findByScoringId(Long scoringId);
}
