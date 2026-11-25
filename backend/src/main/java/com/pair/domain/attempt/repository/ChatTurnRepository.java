package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.ChatTurn;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, Long> {

    List<ChatTurn> findByAttemptIdOrderByTurnNoAsc(Long attemptId);

    /** 이 중 대화가 한 턴이라도 있는 응시 */
    @Query("select distinct t.attemptId from ChatTurn t where t.attemptId in :attemptIds")
    List<Long> findAttemptIdsWithTurns(Collection<Long> attemptIds);

    Optional<ChatTurn> findByAttemptIdAndTurnNo(Long attemptId, int turnNo);
}
