package com.pair.domain.question.repository;

import com.pair.domain.question.entity.QuestionResult;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionResultRepository extends JpaRepository<QuestionResult, QuestionResult.Key> {

    List<QuestionResult> findByScoringId(Long scoringId);
}
