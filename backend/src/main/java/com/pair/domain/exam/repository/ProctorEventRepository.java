package com.pair.domain.exam.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.ProctorEvent;

public interface ProctorEventRepository extends JpaRepository<ProctorEvent, Long> {

    List<ProctorEvent> findByParticipantIdOrderByStartedAtAsc(Long participantId);

    /** 대상자의 이벤트 수 (서버 추론 이벤트 수신 한도 확인용) */
    long countByParticipantId(Long participantId);

    /**
     * 서버 추론 회차에서 받은 지 얼마 안 된 카메라 이벤트 중 아직 증거 구간 사진이 없는 것 (백그라운드 승격 후보).
     * 승격 예정 시각 전의 것은 서비스가 걸러낸다.
     */
    @Query("""
            select e from ProctorEvent e
            where e.receivedAt >= :since and e.type in :types
              and not exists (select 1 from ProctorEventFrame f where f.eventId = e.id)
              and e.participantId in (select p.id from ExamParticipant p where p.session.serverVision = true)
            order by e.receivedAt
            """)
    List<ProctorEvent> findUnpromoted(@Param("since") Instant since, @Param("types") Collection<ProctorEvent.Type> types, Pageable page);

    /** 재전송으로 온 같은 이벤트 찾기 (브라우저가 만든 고유값) */
    java.util.Optional<ProctorEvent> findByParticipantIdAndClientId(Long participantId, String clientId);

    /** 대상자에게 이미 붙은 사진 수 (사진 상한 확인용) */
    long countByParticipantIdAndSnapshotKeyIsNotNull(Long participantId);

    /**
     * 사진 연결만 바꾸는 UPDATE. 업로드가 끝나는 사이 담당자가 판정을 저장해도 다른 컬럼을 덮어쓰지 않는다.
     * 이미 사진이 붙은 이벤트는 건드리지 않는다 (두 번 불러도 안전).
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ProctorEvent e set e.snapshotKey = :key, e.snapshotBytes = :bytes where e.id = :id and e.snapshotKey is null")
    int attachSnapshot(@Param("id") Long id, @Param("key") String key, @Param("bytes") int bytes);

    /** 회차 전체 이벤트 (콘솔 실시간·감독 화면, 대상자마다 따로 부르지 않게 한 번에) */
    @Query("""
            select e from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.id = :sessionId
            order by e.startedAt
            """)
    List<ProctorEvent> findBySessionId(@Param("sessionId") Long sessionId);

    /** 회차별 아직 판정하지 않은 카메라 이벤트 수 (회차 목록용, 회차마다 따로 세지 않는다). 브라우저 이벤트는 판정 대상이 아니라 세지 않는다 */
    interface SessionOpenCount {
        Long getSessionId();
        long getOpen();
    }

    @Query("""
            select p.session.id as sessionId, count(e) as open from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.id in :sessionIds and e.review is null and e.type in :cameraTypes
            group by p.session.id
            """)
    List<SessionOpenCount> countUnreviewedBySessionIds(@Param("sessionIds") Collection<Long> sessionIds, @Param("cameraTypes") Collection<ProctorEvent.Type> cameraTypes);

    default List<SessionOpenCount> countUnreviewedBySessionIds(Collection<Long> sessionIds) {
        return countUnreviewedBySessionIds(sessionIds, ProctorEvent.Type.cameraTypes());
    }

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

    /** 기본 보관 기간이 지난 회차 중 아직 지우지 않은 사진이 남은 회차 (연장 여부는 서비스가 가른다) */
    @Query("""
            select distinct p.session.id from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.endsAt < :endedBefore
              and e.snapshotKey is not null and e.snapshotDeletedAt is null
            """)
    List<Long> findSessionIdsWithLiveSnapshots(@Param("endedBefore") Instant endedBefore);

    /** 한 회차의 아직 지우지 않은 사진 */
    @Query("""
            select e from ProctorEvent e, ExamParticipant p
            where p.id = e.participantId and p.session.id = :sessionId
              and e.snapshotKey is not null and e.snapshotDeletedAt is null
            order by e.id
            """)
    List<ProctorEvent> findLiveSnapshotsOfSession(@Param("sessionId") Long sessionId, Pageable page);
}
