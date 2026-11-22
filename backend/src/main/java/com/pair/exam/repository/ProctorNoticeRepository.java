package com.pair.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.exam.domain.ProctorNotice;

public interface ProctorNoticeRepository extends JpaRepository<ProctorNotice, Long> {

    List<ProctorNotice> findByParticipantIdOrderByIdAsc(Long participantId);
}
