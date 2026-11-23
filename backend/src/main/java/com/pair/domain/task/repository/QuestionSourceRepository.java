package com.pair.domain.task.repository;

import com.pair.domain.task.entity.QuestionSource;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionSourceRepository extends JpaRepository<QuestionSource, QuestionSource.Key> {

    List<QuestionSource> findByQuestionId(Long questionId);

    List<QuestionSource> findByQuestionIdIn(java.util.Collection<Long> questionIds);
}
