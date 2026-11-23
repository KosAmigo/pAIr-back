package com.pair.domain.scoring.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 실제 응시 채점 (ERD scorings). status: RUNNING / DONE / ERROR */
@Entity
@Table(name = "scorings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Scoring {

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

    public Scoring(Long attemptId, Long versionId, Instant startedAt) {
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

}
