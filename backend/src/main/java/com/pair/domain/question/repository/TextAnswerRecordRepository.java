package com.pair.domain.question.repository;

import com.pair.domain.question.entity.TextAnswerRecord;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TextAnswerRecordRepository extends JpaRepository<TextAnswerRecord, Long> {

    List<TextAnswerRecord> findByAttemptId(Long attemptId);

    Optional<TextAnswerRecord> findByAttemptIdAndQuestionId(Long attemptId, Long questionId);
}
