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

    /** 모델별 토큰 합계 (회차 AI 비용) */
    interface ModelUsage {
        String getModel();
        long getCalls();
        long getInputTokens();
        long getOutputTokens();
    }

    /** 회차 응시자들의 AI 답변(AI 턴) 토큰을 모델별로 더한다 */
    @Query("""
            select t.model as model, count(t) as calls,
                   coalesce(sum(t.inputTokens), 0) as inputTokens, coalesce(sum(t.outputTokens), 0) as outputTokens
            from ChatTurn t, com.pair.domain.attempt.entity.Attempt a, com.pair.domain.exam.entity.ExamParticipant p
            where t.attemptId = a.id and a.participantId = p.id and p.session.id = :sessionId and t.role = 'AI'
            group by t.model
            """)
    List<ModelUsage> sumBySession(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);

    /** 관리자 통계: 응시 모드·모델별 AI 답변 토큰 */
    interface ModeModelUsage {
        com.pair.domain.attempt.entity.Attempt.Mode getMode();
        String getModel();
        long getCalls();
        long getInputTokens();
        long getOutputTokens();
    }

    /** 기간 안에 만든 AI 답변(AI 턴)의 토큰을 응시 모드·모델별로 더한다. 비용이므로 관리자 응시도 포함 */
    @org.springframework.data.jpa.repository.Query("""
            select a.mode as mode, t.model as model, count(t) as calls,
                   coalesce(sum(t.inputTokens), 0) as inputTokens, coalesce(sum(t.outputTokens), 0) as outputTokens
            from ChatTurn t, com.pair.domain.attempt.entity.Attempt a
            where t.attemptId = a.id and t.role = 'AI' and t.createdAt >= :from and t.createdAt < :to
            group by a.mode, t.model
            """)
    java.util.List<ModeModelUsage> sumByModeAndModelBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
