package com.pair.attempt.db;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 채점 리포트 (V5 attempt_reports). 응시당 최신 1건 */
@Entity
@Table(name = "attempt_reports")
public class AttemptReport {

    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String report;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttemptReport() {}

    public AttemptReport(Long attemptId, String report, Instant at) {
        this.attemptId = attemptId;
        this.report = report;
        this.updatedAt = at;
    }

    public void update(String report, Instant at) {
        this.report = report;
        this.updatedAt = at;
    }

    public Long getAttemptId() { return attemptId; }
    public String getReport() { return report; }
    public Instant getUpdatedAt() { return updatedAt; }
}
