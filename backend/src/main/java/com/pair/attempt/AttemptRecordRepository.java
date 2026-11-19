package com.pair.attempt;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRecordRepository extends JpaRepository<AttemptRecord, Long> {

    /** 내 자가진단 기록 (최근 순) */
    List<AttemptRecord> findByUserIdAndModeOrderByStartedAtDesc(Long userId, AttemptRecord.Mode mode);

    List<AttemptRecord> findByParticipantIdOrderByIdAsc(Long participantId);

    /** 여러 대상자의 응시를 한 번에 (목록 화면에서 대상자마다 따로 읽지 않도록) */
    List<AttemptRecord> findByParticipantIdIn(Collection<Long> participantIds);

    boolean existsByParticipantId(Long participantId);

    /** 회차별 제출 완료 인원: 응시가 있고, 제출 안 한 응시가 하나도 없는 대상자 수 */
    interface SessionSubmitted {
        Long getSessionId();
        long getSubmitted();
    }

    @Query("""
            select p.session.id as sessionId, count(p) as submitted
            from com.pair.exam.domain.ExamParticipant p
            where p.session.id in :sessionIds
              and exists (select 1 from AttemptRecord a where a.participantId = p.id)
              and not exists (select 1 from AttemptRecord a where a.participantId = p.id and a.submittedAt is null)
            group by p.session.id
            """)
    List<SessionSubmitted> countSubmittedBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
