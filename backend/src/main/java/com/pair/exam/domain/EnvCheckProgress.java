package com.pair.exam.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 입장 대기 중 환경 점검 진행 상태. 대상자당 최신 1건을 덮어쓴다. */
@Entity
@Table(name = "env_check_progress")
public class EnvCheckProgress {

    @Id
    @Column(name = "participant_id")
    private Long participantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvCheckProgress() {}

    public EnvCheckProgress(Long participantId, String result, Instant updatedAt) {
        this.participantId = participantId;
        this.result = result;
        this.updatedAt = updatedAt;
    }

    public void update(String result, Instant at) {
        this.result = result;
        this.updatedAt = at;
    }

    public Long getParticipantId() { return participantId; }
    public String getResult() { return result; }
    public Instant getUpdatedAt() { return updatedAt; }
}
