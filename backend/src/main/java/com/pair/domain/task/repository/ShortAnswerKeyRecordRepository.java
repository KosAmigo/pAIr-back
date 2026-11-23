package com.pair.domain.task.repository;

import com.pair.domain.task.entity.ShortAnswerKeyRecord;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortAnswerKeyRecordRepository extends JpaRepository<ShortAnswerKeyRecord, Long> {

    List<ShortAnswerKeyRecord> findByQuestionIdOrderByIdAsc(Long questionId);

    List<ShortAnswerKeyRecord> findByQuestionIdInOrderByIdAsc(java.util.Collection<Long> questionIds);
}
