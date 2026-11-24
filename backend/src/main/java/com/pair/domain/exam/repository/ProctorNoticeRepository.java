package com.pair.domain.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.domain.exam.entity.ProctorNotice;

public interface ProctorNoticeRepository extends JpaRepository<ProctorNotice, Long> {

    List<ProctorNotice> findByParticipantIdOrderByIdAsc(Long participantId);

    /** 안내 메시지를 한 번이라도 받은 대상자 번호 (목록에서 한꺼번에 확인용) */
    @org.springframework.data.jpa.repository.Query("select distinct n.participantId from ProctorNotice n where n.participantId in :ids")
    List<Long> findParticipantIdsWithNotice(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
