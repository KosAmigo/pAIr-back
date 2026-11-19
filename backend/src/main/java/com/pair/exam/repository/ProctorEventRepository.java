package com.pair.exam.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.exam.domain.ProctorEvent;

public interface ProctorEventRepository extends JpaRepository<ProctorEvent, Long> {

    List<ProctorEvent> findByParticipantIdOrderByStartedAtAsc(Long participantId);

    /** 회차 전체 이벤트 (콘솔 실시간·감독 화면, 대상자마다 따로 부르지 않게 한 번에) */
    @Query("""
            select e from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.id = :sessionId
            order by e.startedAt
            """)
    List<ProctorEvent> findBySessionId(@Param("sessionId") Long sessionId);

    /** 회차별 아직 판정하지 않은 이벤트 수 (회차 목록용, 회차마다 따로 세지 않는다) */
    interface SessionOpenCount {
        Long getSessionId();
        long getOpen();
    }

    @Query("""
            select p.session.id as sessionId, count(e) as open from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.id in :sessionIds and e.review is null
            group by p.session.id
            """)
    List<SessionOpenCount> countUnreviewedBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 대상자·유형별 건수 (회차 결과 화면용, 대상자 전원을 한 번에) */
    interface TypeCount {
        Long getParticipantId();
        ProctorEvent.Type getType();
        long getCount();
    }

    @Query("""
            select e.participantId as participantId, e.type as type, count(e) as count
            from ProctorEvent e where e.participantId in :participantIds group by e.participantId, e.type
            """)
    List<TypeCount> countByParticipantAndType(@Param("participantIds") Collection<Long> participantIds);

    @Query("select coalesce(sum(e.snapshotBytes), 0) from ProctorEvent e where e.participantId in :participantIds")
    long sumSnapshotBytes(@Param("participantIds") Collection<Long> participantIds);

    /**
     * 보관 기간이 지난 사진: 회차 종료 시각이 endedBefore 보다 앞선 회차의 사진.
     * 한 번에 너무 많이 잡지 않게 Pageable 로 끊는다.
     */
    @Query("""
            select e from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.endsAt < :endedBefore
              and e.snapshotKey is not null and e.snapshotDeletedAt is null
            order by e.id
            """)
    List<ProctorEvent> findExpiredSnapshots(@Param("endedBefore") Instant endedBefore, Pageable page);
}
