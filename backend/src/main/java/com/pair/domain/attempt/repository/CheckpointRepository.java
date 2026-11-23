package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.Checkpoint;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    List<Checkpoint> findByAttemptIdOrderByTurnNoAsc(Long attemptId);

    List<Checkpoint> findByAttemptIdIn(java.util.Collection<Long> attemptIds);
}
