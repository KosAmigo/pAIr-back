package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptReportRepository extends JpaRepository<AttemptReport, Long> {
}
