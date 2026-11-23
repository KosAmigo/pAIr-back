package com.pair.domain.exam.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.entity.ProctorEventFrame;

public interface ProctorEventFrameRepository extends JpaRepository<ProctorEventFrame, Long> {

    List<ProctorEventFrame> findByEventIdOrderByOffsetMsAscSourceAsc(Long eventId);

    Optional<ProctorEventFrame> findByEventIdAndSourceAndOffsetMs(Long eventId, ProctorEvent.Source source, int offsetMs);

    long countByEventId(Long eventId);

    /** 대상자 한 명이 증거 구간을 붙인 이벤트 수 (상한 확인용) */
    @Query("""
            select count(distinct f.eventId) from ProctorEventFrame f, ProctorEvent e
            where f.eventId = e.id and e.participantId = :participantId
            """)
    long countClippedEventsOf(@Param("participantId") Long participantId);

    interface EventCount {
        Long getEventId();
        long getCount();
    }

    /** 회차의 이벤트별 증거 사진 수 (삭제된 것 제외 안 함: 타임라인 표시용 총 장수) */
    @Query("select f.eventId as eventId, count(f) as count from ProctorEventFrame f where f.sessionId = :sessionId group by f.eventId")
    List<EventCount> countBySession(@Param("sessionId") Long sessionId);

    @Query("select distinct f.sessionId from ProctorEventFrame f where f.deletedAt is null and f.sessionId in :sessionIds")
    List<Long> sessionIdsWithLiveFrames(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select distinct f.sessionId from ProctorEventFrame f where f.deletedAt is null")
    List<Long> allSessionIdsWithLiveFrames();

    @Query("select f from ProctorEventFrame f where f.sessionId = :sessionId and f.deletedAt is null order by f.id")
    List<ProctorEventFrame> findLiveOfSession(@Param("sessionId") Long sessionId, Pageable page);
}
