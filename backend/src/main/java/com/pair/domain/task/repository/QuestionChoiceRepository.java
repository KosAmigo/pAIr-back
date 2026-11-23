package com.pair.domain.task.repository;

import com.pair.domain.task.entity.QuestionChoice;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionChoiceRepository extends JpaRepository<QuestionChoice, Long> {

    List<QuestionChoice> findByQuestionIdOrderByIdAsc(Long questionId);

    List<QuestionChoice> findByQuestionIdInOrderBySeqAsc(java.util.Collection<Long> questionIds);
}
