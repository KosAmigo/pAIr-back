package com.pair.domain.company.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.security.InviteTokenService;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;
import com.pair.domain.auth.service.EmailVerificationService;
import com.pair.domain.auth.service.WithdrawnEmailHashService;
import com.pair.infra.mail.MailDeliveryException;
import com.pair.domain.auth.repository.RefreshSessionRepository;

@Service
public class CorporateAccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final AuthAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final EmailVerificationService emailVerification;
    private final CompanyAccountMailService mailer;
    private final WithdrawnEmailHashService withdrawnEmailHasher;

    public CorporateAccountService(AuthAccountRepository accounts, RefreshSessionRepository sessions,
            EmailVerificationService emailVerification, CompanyAccountMailService mailer,
            WithdrawnEmailHashService withdrawnEmailHasher) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.emailVerification = emailVerification;
        this.mailer = mailer;
        this.withdrawnEmailHasher = withdrawnEmailHasher;
    }

    @Transactional
    public AuthAccount create(String email, String name, CurrentUser.Role role, Long companyId) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (accounts.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
        emailVerification.removeForEmail(normalized);
        String password = temporaryPassword();
        AuthAccount account = newOrReactivated(normalized, name.trim(), PASSWORD_ENCODER.encode(password));
        account.startCompanyOnboarding(role, companyId);
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
        sendCredentials(account, password);
        return account;
    }

    @Transactional
    public AuthAccount signupProctor(String email, String name, String password, Long companyId) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (accounts.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
        AuthAccount account = newOrReactivated(normalized, name.trim(), hashSignupPassword(password));
        account.activateAsCompanyMember(CurrentUser.Role.PROCTOR, companyId);
        try {
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
    }

    @Transactional
    public AuthAccount reissue(Long userId) {
        AuthAccount account = accounts.lockById(userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("회원을 찾을 수 없습니다."));
        if (!account.isActive() || account.getCompanyId() == null
                || (account.getRole() != CurrentUser.Role.MANAGER && account.getRole() != CurrentUser.Role.PROCTOR)
                || (!account.isPasswordChangeRequired() && account.isEmailVerified())) {
            throw new IllegalStateException("초기 인증이 끝난 기업 계정은 재발급할 수 없습니다.");
        }
        String password = temporaryPassword();
        account.resetOnboarding(PASSWORD_ENCODER.encode(password));
        sessions.revokeAll(userId);
        emailVerification.removeForEmail(account.getEmail());
        sendCredentials(account, password);
        return account;
    }

    /** 탈퇴한 회원이면 기존 계정을 되살린다. 새로 만들면 다음 탈퇴 때 탈퇴 이메일 해시가 겹친다. */
    private AuthAccount newOrReactivated(String email, String name, String passwordHash) {
        AuthAccount account = accounts.findByEmailIgnoreCase(withdrawnEmailHasher.hash(email)).orElse(null);
        if (account == null) return new AuthAccount(email, name, passwordHash);
        account.reactivate(email, name, passwordHash);
        return account;
    }

    private void sendCredentials(AuthAccount account, String password) {
        try {
            mailer.sendCredentials(account, password);
            account.markOnboardingMail("SENT");
        } catch (MailDeliveryException | org.springframework.mail.MailException e) {
            account.markOnboardingMail("FAILED");
        }
    }

    private static String temporaryPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashSignupPassword(String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        return PASSWORD_ENCODER.encode(password);
    }

    public static String newInvalidPasswordHash() {
        return PASSWORD_ENCODER.encode(InviteTokenService.next());
    }
}
