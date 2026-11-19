package com.pair.web.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 원문은 노출하지 않고 해시된 로그인 토큰과 만료 정보를 저장하는 세션 엔티티. */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AuthAccount account;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AuthSession() {}

    public AuthSession(String tokenHash, AuthAccount account, Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.account = account;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public AuthAccount getAccount() { return account; }
    public Instant getExpiresAt() { return expiresAt; }
}
