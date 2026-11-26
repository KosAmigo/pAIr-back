package com.pair.domain.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.domain.exam.entity.ProctorStreamAccessLog;

public interface ProctorStreamAccessLogRepository extends JpaRepository<ProctorStreamAccessLog, Long> {
}
