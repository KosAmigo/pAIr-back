package com.pair.domain.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.domain.exam.entity.ProctorConsent;

public interface ProctorConsentRepository extends JpaRepository<ProctorConsent, Long> {
}
