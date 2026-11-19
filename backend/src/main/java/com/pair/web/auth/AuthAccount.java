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

/** 이메일, 해시된 비밀번호와 역할을 보관하는 회원 계정 엔티티. */
@Entity
@Table(name = "auth_users")
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthAccount() {}

    public AuthAccount(String email, String name, String passwordHash) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = CurrentUser.Role.USER;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public CurrentUser.Role getRole() { return role; }
    public Long getCompanyId() { return companyId; }
}
