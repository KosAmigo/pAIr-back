package com.pair.domain.exam.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.ProctorStreamPack;

public interface ProctorStreamPackRepository extends JpaRepository<ProctorStreamPack, Long> {

    Optional<ProctorStreamPack> findByParticipantIdAndChannelAndStartAt(Long participantId, ProctorStreamPack.Channel channel, Instant startAt);

    /** 시간 범위 (from, to) 와 겹치는 묶음 (지워진 것 제외), 시간순 */
    @Query("""
            select p from ProctorStreamPack p
            where p.participantId = :participantId and p.channel = :channel and p.deletedAt is null
              and p.startAt < :to and p.endAt > :from
            order by p.startAt
            """)
    List<ProctorStreamPack> findOverlapping(@Param("participantId") Long participantId, @Param("channel") ProctorStreamPack.Channel channel,
            @Param("from") Instant from, @Param("to") Instant to);

    @Query("select distinct p.sessionId from ProctorStreamPack p where p.deletedAt is null")
    List<Long> allSessionIdsWithLivePacks();

    @Query("select p from ProctorStreamPack p where p.sessionId = :sessionId and p.deletedAt is null order by p.id")
    List<ProctorStreamPack> findLiveOfSession(@Param("sessionId") Long sessionId, Pageable page);
}
