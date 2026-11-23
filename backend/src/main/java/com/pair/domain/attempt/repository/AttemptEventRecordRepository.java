package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptEventRecord;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptEventRecordRepository extends JpaRepository<AttemptEventRecord, Long> {

    List<AttemptEventRecord> findByAttemptIdOrderByIdAsc(Long attemptId);
}
