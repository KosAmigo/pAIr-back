package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.Attempt;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    /** 내 자가진단 기록 (최근 순) */
    List<Attempt> findByUserIdAndModeOrderByStartedAtDesc(Long userId, Attempt.Mode mode);

    List<Attempt> findByParticipantIdOrderByIdAsc(Long participantId);

    /** 상태별 응시 수 (모니터링: 채점 대기 건수) */
    long countByStatus(com.pair.domain.attempt.dto.AttemptStateDto.Status status);

    /** 상태별 응시 ID (서버 시작 때 채점이 끊긴 응시 찾기) */
    @Query("select a.id from Attempt a where a.status = :status order by a.id")
    List<Long> findIdsByStatus(@Param("status") com.pair.domain.attempt.dto.AttemptStateDto.Status status);

    /** 여러 대상자의 응시를 한 번에 (목록 화면에서 대상자마다 따로 읽지 않도록) */
    List<Attempt> findByParticipantIdIn(Collection<Long> participantIds);

    boolean existsByParticipantId(Long participantId);

    /** 이 체크포인트들에서 갈라진 응시 (복기 트리의 자식) */
    List<Attempt> findByForkedFromCheckpointIdIn(Collection<Long> checkpointIds);

    /** 후속 질문까지 마친 응시 ID (최근 순). 채점 툴의 "내 응시" 목록 */
    @Query("select a.id from Attempt a where a.followupsDoneAt is not null order by a.id desc")
    List<Long> findFinishedIds();

    /**
     * 다시 채점 시작: 지금 상태가 expected 일 때만 SCORING 으로 바꾼다. 바뀐 행 수(0이면 다른 요청이 먼저 바꿨다)
     * 상태 검사와 변경을 DB 에서 한 번에 해서 동시에 눌러도 하나만 성공한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Attempt a set a.status = com.pair.domain.attempt.dto.AttemptStateDto.Status.SCORING, a.error = null
            where a.id = :id and a.status = :expected and a.followupsDoneAt is not null
            """)
    int startRescoring(@Param("id") Long id, @Param("expected") com.pair.domain.attempt.dto.AttemptStateDto.Status expected);

    /** 회차별 제출 완료 인원: 응시가 있고, 제출 안 한 응시가 하나도 없는 대상자 수 */
    interface SessionSubmitted {
        Long getSessionId();
        long getSubmitted();
    }

    @Query("""
            select p.session.id as sessionId, count(p) as submitted
            from com.pair.domain.exam.entity.ExamParticipant p
            where p.session.id in :sessionIds
              and exists (select 1 from Attempt a where a.participantId = p.id)
              and not exists (select 1 from Attempt a where a.participantId = p.id and a.submittedAt is null)
            group by p.session.id
            """)
    List<SessionSubmitted> countSubmittedBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
