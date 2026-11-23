package com.pair.domain.question.repository;

import com.pair.domain.question.entity.TextAnswer;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TextAnswerRepository extends JpaRepository<TextAnswer, Long> {

    List<TextAnswer> findByAttemptId(Long attemptId);

    Optional<TextAnswer> findByAttemptIdAndQuestionId(Long attemptId, Long questionId);
}
