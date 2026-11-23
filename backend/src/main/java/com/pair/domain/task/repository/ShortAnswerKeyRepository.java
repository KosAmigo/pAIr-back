package com.pair.domain.task.repository;

import com.pair.domain.task.entity.ShortAnswerKey;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortAnswerKeyRepository extends JpaRepository<ShortAnswerKey, Long> {

    List<ShortAnswerKey> findByQuestionIdOrderByIdAsc(Long questionId);

    List<ShortAnswerKey> findByQuestionIdInOrderByIdAsc(java.util.Collection<Long> questionIds);
}
