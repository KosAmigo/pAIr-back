package com.pair.web.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회원 계정. ERD 의 users 테이블(V1)을 그대로 쓴다. 응시·회차가 가리키는 user_id 와 같은 ID 라서 따로 계정 테이블을 두지 않는다.
 * 가입하면 member_status 는 ACTIVE 로 둔다.
 */
@Entity
@Table(name = "users")
public class AuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CurrentUser.Role role;

    @Column(name = "company_id")
    private Long companyId;

    /** ACTIVE, WITHDRAWN, PENDING (users.member_status). 로그인은 ACTIVE 만 된다 */
    @Column(name = "member_status", nullable = false, length = 20)
    private String memberStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    protected AuthAccount() {}

    public AuthAccount(String email, String name, String passwordHash) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = CurrentUser.Role.USER;
        this.memberStatus = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public CurrentUser.Role getRole() { return role; }
    public Long getCompanyId() { return companyId; }
    public boolean isActive() { return "ACTIVE".equals(memberStatus); }
    public long getTokenVersion() { return tokenVersion; }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        tokenVersion++;
    }

    public void withdraw(String anonymousEmail, String invalidPasswordHash, Instant now) {
        email = anonymousEmail;
        name = "UNKOWN";
        passwordHash = invalidPasswordHash;
        memberStatus = "WITHDRAWN";
        deletedAt = now;
        tokenVersion++;
    }
}
