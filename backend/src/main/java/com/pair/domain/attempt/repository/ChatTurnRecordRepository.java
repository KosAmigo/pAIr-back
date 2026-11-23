package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.ChatTurnRecord;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatTurnRecordRepository extends JpaRepository<ChatTurnRecord, Long> {

    List<ChatTurnRecord> findByAttemptIdOrderByTurnNoAsc(Long attemptId);
    Optional<ChatTurnRecord> findByAttemptIdAndTurnNo(Long attemptId, int turnNo);
}
