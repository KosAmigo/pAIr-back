package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.ChatTurn;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, Long> {

    List<ChatTurn> findByAttemptIdOrderByTurnNoAsc(Long attemptId);
    Optional<ChatTurn> findByAttemptIdAndTurnNo(Long attemptId, int turnNo);
}
