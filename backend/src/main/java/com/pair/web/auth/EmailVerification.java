package com.pair.web.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 가입 이메일 인증 코드의 해시와 사용·만료 상태. */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 60)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected EmailVerification() {}

    public EmailVerification(String email, String codeHash, Instant createdAt, Instant expiresAt) {
        this.email = email;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getCodeHash() { return codeHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getConsumedAt() { return consumedAt; }

    public void replaceCode(String codeHash, Instant createdAt, Instant expiresAt) {
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.failedAttempts = 0;
        this.consumedAt = null;
    }

    public void recordFailure() {
        failedAttempts++;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}
