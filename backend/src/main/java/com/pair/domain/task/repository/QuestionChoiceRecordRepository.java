package com.pair.domain.task.repository;

import com.pair.domain.task.entity.QuestionChoiceRecord;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionChoiceRecordRepository extends JpaRepository<QuestionChoiceRecord, Long> {

    List<QuestionChoiceRecord> findByQuestionIdOrderByIdAsc(Long questionId);

    List<QuestionChoiceRecord> findByQuestionIdInOrderBySeqAsc(java.util.Collection<Long> questionIds);
}
