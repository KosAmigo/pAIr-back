package com.pair.scoring.db;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 실제 응시 채점 (ERD scorings). status: RUNNING / DONE / ERROR */
@Entity
@Table(name = "scorings")
public class ScoringRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scoring_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ScoringRecord() {}

    public ScoringRecord(Long attemptId, Long versionId, Instant startedAt) {
        this.attemptId = attemptId;
        this.versionId = versionId;
        this.status = "RUNNING";
        this.startedAt = startedAt;
    }

    public void done(Instant at) {
        this.status = "DONE";
        this.finishedAt = at;
    }

    public void fail(String error, Instant at) {
        this.status = "ERROR";
        this.error = error;
        this.finishedAt = at;
    }

    public Long getId() { return id; }
    public Long getAttemptId() { return attemptId; }
    public Long getVersionId() { return versionId; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
