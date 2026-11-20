package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionSourceRecordRepository extends JpaRepository<QuestionSourceRecord, QuestionSourceRecord.Key> {

    List<QuestionSourceRecord> findByQuestionId(Long questionId);

    List<QuestionSourceRecord> findByQuestionIdIn(java.util.Collection<Long> questionIds);
}
