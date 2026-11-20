package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionChoiceRecordRepository extends JpaRepository<QuestionChoiceRecord, Long> {

    List<QuestionChoiceRecord> findByQuestionIdOrderByIdAsc(Long questionId);
}
