package com.pair.domain.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.domain.exam.entity.ProctorNotice;

public interface ProctorNoticeRepository extends JpaRepository<ProctorNotice, Long> {

    List<ProctorNotice> findByParticipantIdOrderByIdAsc(Long participantId);
}
