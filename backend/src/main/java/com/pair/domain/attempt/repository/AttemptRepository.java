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

    /** 제출하지 않은 응시만 지운 것으로 표시한다. 바뀐 행 수(0이면 이미 지웠거나 제출을 마친 응시) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Attempt a set a.deletedAt = :at
            where a.id = :id and a.deletedAt is null
              and a.status in (com.pair.domain.attempt.dto.AttemptStateDto.Status.SOLVING, com.pair.domain.attempt.dto.AttemptStateDto.Status.FOLLOWUP)
            """)
    int softDelete(@Param("id") Long id, @Param("at") java.time.Instant at);

    List<Attempt> findByParticipantIdOrderByIdAsc(Long participantId);

    /** 상태별 응시 수 (모니터링: 채점 대기 건수) */
    long countByStatus(com.pair.domain.attempt.dto.AttemptStateDto.Status status);

    /** 회차의 응시 수 (대상자 × 과제) */
    @Query("select count(a) from Attempt a, com.pair.domain.exam.entity.ExamParticipant p where a.participantId = p.id and p.session.id = :sessionId")
    long countBySession(@Param("sessionId") Long sessionId);

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

    /**
     * 후속 질문 마무리: 지금 상태가 FOLLOWUP 일 때만 SCORING 으로 바꾸고 완료 시각을 적는다. 바뀐 행 수(0이면 다른 요청이 먼저 바꿨다).
     * 응시자가 두 번 누르거나 응시자 제출과 시험 종료 자동 마무리가 겹쳐도 하나만 채점을 시작한다.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Attempt a set a.status = com.pair.domain.attempt.dto.AttemptStateDto.Status.SCORING, a.followupsDoneAt = :at
            where a.id = :id and a.status = com.pair.domain.attempt.dto.AttemptStateDto.Status.FOLLOWUP and a.deletedAt is null
            """)
    int startScoring(@Param("id") Long id, @Param("at") java.time.Instant at);

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

    /** 관리자 통계: 응시 모드별 시작·제출 수 */
    interface ModeCount {
        com.pair.domain.attempt.entity.Attempt.Mode getMode();
        long getStarted();
        long getSubmitted();
    }

    /** 기간 안에 시작한 응시를 모드별로 센다. 지운 응시와 관리자가 시험 삼아 푼 응시는 뺀다 */
    @org.springframework.data.jpa.repository.Query("""
            select a.mode as mode, count(a) as started, count(a.submittedAt) as submitted from Attempt a
            where a.deletedAt is null and a.startedAt >= :from and a.startedAt < :to
              and (a.userId is null or a.userId not in (select u.id from com.pair.domain.auth.entity.AuthAccount u where u.role = com.pair.global.security.CurrentUser.Role.ADMIN))
            group by a.mode
            """)
    java.util.List<ModeCount> countByModeBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** 관리자 회원 목록: 회원별 자가진단 응시 요약 */
    interface UserAttemptSummary {
        Long getUserId();
        long getStarted();
        long getSubmitted();
        java.time.Instant getLastStartedAt();
    }

    @org.springframework.data.jpa.repository.Query("""
            select a.userId as userId, count(a) as started, count(a.submittedAt) as submitted, max(a.startedAt) as lastStartedAt
            from Attempt a
            where a.userId in :userIds and a.deletedAt is null and a.mode = com.pair.domain.attempt.entity.Attempt.Mode.SELF
            group by a.userId
            """)
    java.util.List<UserAttemptSummary> summarizeSelfByUsers(@org.springframework.data.repository.query.Param("userIds") java.util.Collection<Long> userIds);
}
