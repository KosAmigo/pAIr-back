package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.JudgeCall;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeCallRepository extends JpaRepository<JudgeCall, Long> {
    List<JudgeCall> findByScoringId(Long scoringId);

    /** 모델별 토큰 합계 (회차 AI 비용). 실패·재채점 호출도 비용이 들었으므로 모두 센다 */
    interface ModelUsage {
        String getModel();
        long getCalls();
        long getInputTokens();
        long getOutputTokens();
        long getCachedTokens();
    }

    @org.springframework.data.jpa.repository.Query("""
            select j.model as model, count(j) as calls, coalesce(sum(j.inputTokens), 0) as inputTokens,
                   coalesce(sum(j.outputTokens), 0) as outputTokens, coalesce(sum(j.cachedTokens), 0) as cachedTokens
            from JudgeCall j, com.pair.domain.scoring.entity.Scoring s, com.pair.domain.attempt.entity.Attempt a,
                 com.pair.domain.exam.entity.ExamParticipant p
            where j.scoringId = s.id and s.attemptId = a.id and a.participantId = p.id and p.session.id = :sessionId
            group by j.model
            """)
    List<ModelUsage> sumBySession(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);
}
