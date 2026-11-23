package com.pair.domain.exam.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 감독 사진 보관 연장 이력 1건. 회차 단위로 쌓이고, (회차, 순번)이 유니크라 동시에 눌러도 최대 횟수를 못 넘긴다.
 */
@Entity
@Table(name = "snapshot_retention_extensions",
        uniqueConstraints = @UniqueConstraint(name = "uq_retention_ext_seq", columnNames = {"session_id", "seq"}))
public class SnapshotRetentionExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "extension_id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 1부터 시작하는 연장 순번 */
    @Column(nullable = false)
    private int seq;

    @Column(name = "extended_by", nullable = false)
    private Long extendedBy;

    @Column(name = "extended_at", nullable = false)
    private Instant extendedAt;

    @Column(nullable = false)
    private int days;

    @Column(name = "expires_before", nullable = false)
    private Instant expiresBefore;

    @Column(name = "expires_after", nullable = false)
    private Instant expiresAfter;

    protected SnapshotRetentionExtension() {}

    public SnapshotRetentionExtension(Long sessionId, int seq, Long extendedBy, Instant extendedAt, int days,
            Instant expiresBefore, Instant expiresAfter) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.extendedBy = extendedBy;
        this.extendedAt = extendedAt;
        this.days = days;
        this.expiresBefore = expiresBefore;
        this.expiresAfter = expiresAfter;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public Long getExtendedBy() { return extendedBy; }
    public Instant getExtendedAt() { return extendedAt; }
    public int getDays() { return days; }
    public Instant getExpiresBefore() { return expiresBefore; }
    public Instant getExpiresAfter() { return expiresAfter; }
}
