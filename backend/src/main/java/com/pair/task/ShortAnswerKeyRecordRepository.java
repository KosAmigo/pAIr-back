package com.pair.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortAnswerKeyRecordRepository extends JpaRepository<ShortAnswerKeyRecord, Long> {

    List<ShortAnswerKeyRecord> findByQuestionIdOrderByIdAsc(Long questionId);

    List<ShortAnswerKeyRecord> findByQuestionIdInOrderByIdAsc(java.util.Collection<Long> questionIds);
}
