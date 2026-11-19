package com.pair.attempt.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptEventRecordRepository extends JpaRepository<AttemptEventRecord, Long> {

    List<AttemptEventRecord> findByAttemptIdOrderByIdAsc(Long attemptId);
}
