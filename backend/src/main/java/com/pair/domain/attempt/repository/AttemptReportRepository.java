package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptReportRepository extends JpaRepository<AttemptReport, Long> {

    /** 관리자 통계: 응시 모드와 리포트 JSON (점수는 서비스에서 읽는다) */
    interface ModeReport {
        com.pair.domain.attempt.entity.Attempt.Mode getMode();
        String getReport();
    }

    /** 기간 안에 시작한 응시의 리포트. 응시 수 집계(AttemptRepository.countByModeBetween)와 같은 조건 */
    @org.springframework.data.jpa.repository.Query("""
            select a.mode as mode, r.report as report from AttemptReport r, com.pair.domain.attempt.entity.Attempt a
            where r.attemptId = a.id and a.deletedAt is null and a.startedAt >= :from and a.startedAt < :to
              and (a.userId is null or a.userId not in (select u.id from com.pair.domain.auth.entity.AuthAccount u where u.role = com.pair.global.security.CurrentUser.Role.ADMIN))
            """)
    java.util.List<ModeReport> findReportsBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
