package com.pair.exam.repository;

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

import com.pair.exam.domain.ExamParticipant;

import jakarta.persistence.LockModeType;

public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {

    /** 회차별 인원 집계 한 줄 (목록 화면에서 회차마다 따로 세지 않도록 한 번에 묶는다) */
    interface SessionCounts {
        Long getSessionId();
        long getTotal();
        long getEntered();
        long getSubmitted();
    }

    @EntityGraph(attributePaths = {"session", "attempts"})
    Optional<ExamParticipant> findByInviteTokenHash(String inviteTokenHash);

    /** 시작·제출처럼 두 번 눌리면 안 되는 요청용. 같은 대상자의 요청은 줄을 세운다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p join fetch p.session where p.inviteTokenHash = :hash")
    Optional<ExamParticipant> lockByInviteTokenHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExamParticipant p where p.session.id = :sessionId order by p.id")
    List<ExamParticipant> lockBySessionId(@Param("sessionId") Long sessionId);

    @EntityGraph(attributePaths = "attempts")
    List<ExamParticipant> findBySessionIdOrderByIdAsc(Long sessionId);

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
              and p.remindedAt is null and p.inviteStatus = com.pair.exam.domain.ExamParticipant.InviteStatus.SENT
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
            select p.session.id as sessionId, count(p) as total,
                   count(p.enteredAt) as entered, count(p.submittedAt) as submitted
            from ExamParticipant p where p.session.id in :sessionIds group by p.session.id
            """)
    List<SessionCounts> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
