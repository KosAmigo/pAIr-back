package com.pair.domain.exam.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.ExamParticipant;

import jakarta.persistence.LockModeType;

public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {

    /** 회차별 인원 집계 한 줄 (목록 화면에서 회차마다 따로 세지 않도록 한 번에 묶는다) */
    interface SessionCounts {
        Long getSessionId();
        long getTotal();
        long getEntered();
    }

    @EntityGraph(attributePaths = "session")
    Optional<ExamParticipant> findByInviteTokenHash(String inviteTokenHash);

    /** 시작·제출처럼 두 번 눌리면 안 되는 요청용. 같은 대상자의 요청은 줄을 세운다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p join fetch p.session where p.inviteTokenHash = :hash")
    Optional<ExamParticipant> lockByInviteTokenHash(@Param("hash") String hash);

    /** 콘솔에서 링크를 되살릴 때용. 응시자의 입장·신호 요청(위 메서드)과 같은 행을 잠가 서로 줄을 선다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p join fetch p.session where p.id = :id")
    Optional<ExamParticipant> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p where p.session.id = :sessionId order by p.id")
    List<ExamParticipant> lockBySessionId(@Param("sessionId") Long sessionId);

    @EntityGraph(attributePaths = "session")
    List<ExamParticipant> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 연결 유예 점검: 진행 중인 회차에서 마지막 신호가 cutoff 보다 오래됐고 아직 링크가 만료되지 않은 대상자 */
    @Query("""
            select p from ExamParticipant p join fetch p.session s
            where p.lastSeenAt is not null and p.lastSeenAt < :cutoff and p.linkExpiredAt is null
              and s.canceledAt is null and s.startsAt <= :now and s.endsAt > :now
            """)
    List<ExamParticipant> findAbsentSince(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Query("select p.id from ExamParticipant p where p.session.id = :sessionId order by p.id")
    List<Long> findIdsBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 하루 전 알림 대상: 시작이 (now, until] 사이이고 아직 알림을 안 보냈으며, 초대 메일을 시작 24시간보다 전에 받은 사람.
     * 시작 24시간 안에 등록된 사람은 초대 메일이 곧 알림이라 다시 보내지 않는다.
     * 시작 시각이 회차마다 달라 "시작 - 24시간" 비교는 서비스에서 한 번 더 한다.
     */
    @Query("""
            select p from ExamParticipant p join fetch p.session s
            where s.canceledAt is null and s.startsAt > :now and s.startsAt <= :until
              and p.remindedAt is null and p.inviteStatus = com.pair.domain.exam.entity.ExamParticipant.InviteStatus.SENT
              and p.id > :afterId
            order by p.id
            """)
    List<ExamParticipant> findReminderCandidates(@Param("now") Instant now, @Param("until") Instant until,
            @Param("afterId") long afterId, org.springframework.data.domain.Pageable page);

    /**
     * 초대 메일 결과 기록. 엔티티를 다시 저장하면 그 사이 응시자가 입장·시작한 기록을 덮어쓰므로 이 두 컬럼만 바꾼다.
     * 토큰이 그 사이 다시 발급됐으면(해시가 다르면) 옛 메일 결과는 버린다.
     */
    @Modifying
    @Query("""
            update ExamParticipant p set p.inviteStatus = :status, p.inviteError = :error
            where p.id = :id and p.inviteTokenHash = :hash
            """)
    int recordInviteResult(@Param("id") Long id, @Param("hash") String hash,
            @Param("status") ExamParticipant.InviteStatus status, @Param("error") String error);

    /** 알림 발송 자리 잡기. 같은 이유로 reminded_at 만 바꾼다 */
    @Modifying
    @Query("update ExamParticipant p set p.remindedAt = :at where p.id in :ids and p.remindedAt is null")
    int markReminded(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    @Query("select p.email from ExamParticipant p where p.session.id = :sessionId and lower(p.email) in :emails")
    List<String> findExistingEmails(@Param("sessionId") Long sessionId, @Param("emails") Collection<String> emails);

    @Query("""
            select p.session.id as sessionId, count(p) as total, count(p.enteredAt) as entered
            from ExamParticipant p where p.session.id in :sessionIds group by p.session.id
            """)
    List<SessionCounts> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 관리자 통계: 기간 안에 시작하는(취소하지 않은) 회차의 대상자 수 */
    @org.springframework.data.jpa.repository.Query("select count(p) from ExamParticipant p where p.session.canceledAt is null and p.session.startsAt >= :from and p.session.startsAt < :to")
    long countInSessionsStartingBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
