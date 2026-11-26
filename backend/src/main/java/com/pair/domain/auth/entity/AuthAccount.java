package com.pair.domain.auth.entity;

import com.pair.global.security.CurrentUser;
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

    @Column(nullable = false, unique = true, length = 255)
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

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @Column(name = "onboarding_mail_status", nullable = false, length = 10)
    private String onboardingMailStatus = "SENT";

    protected AuthAccount() {}

    public AuthAccount(String email, String name, String passwordHash) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = CurrentUser.Role.USER;
        this.memberStatus = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public AuthAccount(String email, String name, String passwordHash, CurrentUser.Role role, Long companyId) {
        this(email, name, passwordHash);
        startCompanyOnboarding(role, companyId);
    }

    /** 기업 계정으로 배정하고 이메일 인증과 비밀번호 변경을 요구한다. */
    public void startCompanyOnboarding(CurrentUser.Role role, Long companyId) {
        if (role != CurrentUser.Role.MANAGER && role != CurrentUser.Role.PROCTOR) {
            throw new IllegalArgumentException("기업 계정 역할이 올바르지 않습니다.");
        }
        if (companyId == null) throw new IllegalArgumentException("기업 계정은 회사가 있어야 합니다.");
        this.role = role;
        this.companyId = companyId;
        this.emailVerified = false;
        this.passwordChangeRequired = true;
        this.onboardingMailStatus = "PENDING";
    }

    public void activateAsCompanyMember(CurrentUser.Role role, Long companyId) {
        if (role != CurrentUser.Role.MANAGER && role != CurrentUser.Role.PROCTOR) {
            throw new IllegalArgumentException("기업 계정 역할이 올바르지 않습니다.");
        }
        if (companyId == null) throw new IllegalArgumentException("기업 계정은 회사가 있어야 합니다.");
        this.role = role;
        this.companyId = companyId;
        this.emailVerified = true;
        this.passwordChangeRequired = false;
        this.onboardingMailStatus = "SENT";
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public String getMemberStatus() { return memberStatus; }
    public CurrentUser.Role getRole() { return role; }
    public Long getCompanyId() { return companyId; }
    public boolean isActive() { return "ACTIVE".equals(memberStatus); }
    public long getTokenVersion() { return tokenVersion; }
    public boolean isEmailVerified() { return emailVerified; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public String getOnboardingMailStatus() { return onboardingMailStatus; }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
        tokenVersion++;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public void markOnboardingMail(String status) {
        this.onboardingMailStatus = status;
    }

    /** 온보딩 메일로 보낼 임시 비밀번호. 발급할 때 이미 세션과 토큰을 무효화했으므로 tokenVersion 은 그대로 둔다. */
    public void setTemporaryPassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changeRole(CurrentUser.Role role) {
        this.role = role;
        this.tokenVersion++;
    }

    public void resetOnboarding(String passwordHash) {
        this.passwordHash = passwordHash;
        this.emailVerified = false;
        this.passwordChangeRequired = true;
        this.onboardingMailStatus = "PENDING";
        this.tokenVersion++;
    }

    public void withdraw(String anonymousEmail, String invalidPasswordHash, Instant now) {
        email = anonymousEmail;
        name = "UNKOWN";
        passwordHash = invalidPasswordHash;
        memberStatus = "WITHDRAWN";
        deletedAt = now;
        emailVerified = false;
        passwordChangeRequired = true;
        tokenVersion++;
    }

    public void deactivate(String anonymousEmail, String invalidPasswordHash, Instant now) {
        withdraw(anonymousEmail, invalidPasswordHash, now);
    }

    public void reactivate(String email, String name, String passwordHash) {
        if (!"WITHDRAWN".equals(memberStatus)) {
            throw new IllegalStateException("탈퇴한 회원만 재가입할 수 있습니다.");
        }
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = CurrentUser.Role.USER;
        this.companyId = null;
        this.memberStatus = "ACTIVE";
        this.deletedAt = null;
        this.emailVerified = true;
        this.passwordChangeRequired = false;
        this.onboardingMailStatus = "SENT";
    }
}
