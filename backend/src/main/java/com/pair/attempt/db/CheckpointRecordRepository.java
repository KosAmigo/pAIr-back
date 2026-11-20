package com.pair.attempt.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckpointRecordRepository extends JpaRepository<CheckpointRecord, Long> {

    List<CheckpointRecord> findByAttemptIdOrderByTurnNoAsc(Long attemptId);

    List<CheckpointRecord> findByAttemptIdIn(java.util.Collection<Long> attemptIds);
}
