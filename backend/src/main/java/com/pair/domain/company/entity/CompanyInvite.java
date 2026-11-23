package com.pair.domain.company.entity;

import java.time.Instant;

import com.pair.global.security.CurrentUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_invites")
public class CompanyInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invite_id")
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrentUser.Role role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "invited_by", nullable = false)
    private Long invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_user_id", unique = true)
    private Long acceptedUserId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyInvite() {}

    public CompanyInvite(Long companyId, String email, CurrentUser.Role role, String tokenHash,
            Long invitedBy, Instant expiresAt, Instant createdAt) {
        this.companyId = companyId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        revokedAt = now;
    }

    public void accept(Long userId, Instant now) {
        if (acceptedAt != null) throw new IllegalStateException("이미 사용된 초대입니다.");
        acceptedUserId = userId;
        acceptedAt = now;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getEmail() { return email; }
    public CurrentUser.Role getRole() { return role; }
    public String getTokenHash() { return tokenHash; }
    public Long getInvitedBy() { return invitedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getAcceptedUserId() { return acceptedUserId; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
