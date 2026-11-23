package com.pair.domain.question.repository;

import com.pair.domain.question.entity.ChoiceAnswer;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChoiceAnswerRepository extends JpaRepository<ChoiceAnswer, ChoiceAnswer.Key> {

    List<ChoiceAnswer> findByAttemptId(Long attemptId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChoiceAnswer a where a.attemptId = :attemptId and a.questionId = :questionId")
    void deleteByAttemptAndQuestion(@Param("attemptId") Long attemptId, @Param("questionId") Long questionId);
}
