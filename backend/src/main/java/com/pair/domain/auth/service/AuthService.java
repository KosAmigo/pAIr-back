package com.pair.domain.auth.service;

import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.dto.LoginRequest;
import com.pair.domain.auth.dto.PasswordChangeRequest;
import com.pair.domain.auth.dto.SignupRequest;
import com.pair.domain.auth.dto.UserResponse;
import com.pair.domain.auth.dto.WithdrawalRequest;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.entity.RefreshSession;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.repository.RefreshSessionRepository;
import com.pair.domain.company.entity.Company;
import com.pair.domain.company.repository.CompanyRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.error.ForbiddenException;
import com.pair.global.error.UnauthorizedException;
import com.pair.global.security.CurrentUser;
import com.pair.global.security.JwtTokens;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.security.InviteTokenService;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.exam.repository.ExamSessionRepository;

/** 계정 생성·비밀번호 검증과 JWT 발급, 확인, 재발급 및 폐기를 처리한다. */
@Service
public class AuthService {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final AuthAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final JwtTokens tokens;
    private final CreditService credits;
    private final EmailVerificationService emailVerification;
    private final ExamSessionRepository examSessions;
    private final WithdrawnEmailHashService withdrawnEmailHasher;
    private final CompanyRepository companies;

    public AuthService(AuthAccountRepository accounts, RefreshSessionRepository sessions, JwtTokens tokens,
            CreditService credits, EmailVerificationService emailVerification, ExamSessionRepository examSessions,
            WithdrawnEmailHashService withdrawnEmailHasher, CompanyRepository companies) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.credits = credits;
        this.emailVerification = emailVerification;
        this.examSessions = examSessions;
        this.withdrawnEmailHasher = withdrawnEmailHasher;
        this.companies = companies;
    }

    public void requestEmailVerification(String email) {
        String normalizedEmail = normalizeEmail(email);
        if (accounts.existsByEmailIgnoreCase(normalizedEmail)) throw new ConflictException("이미 가입된 이메일입니다.");
        emailVerification.sendCode(normalizedEmail);
    }

    @Transactional
    public void requestOnboardingEmailVerification(String accessToken) {
        AuthAccount account = lockCurrentAccount(requireAccess(accessToken));
        if (account.isEmailVerified()) throw new ConflictException("이메일 인증이 이미 완료되었습니다.");
        emailVerification.sendOnboardingCode(account.getEmail());
    }

    @Transactional
    public void verifyOnboardingEmail(String accessToken, String code) {
        AuthAccount account = lockCurrentAccount(requireAccess(accessToken));
        if (account.isEmailVerified()) throw new ConflictException("이메일 인증이 이미 완료되었습니다.");
        EmailVerificationService.ConsumeResult verification = emailVerification.consume(account.getEmail(), code);
        if (verification != EmailVerificationService.ConsumeResult.CONSUMED) {
            throw new IllegalArgumentException(verification.message());
        }
        account.markEmailVerified();
    }

    /** 인증된 이메일로 일반 회원을 생성하고 로그인 상태로 사용할 세션 토큰을 발급한다. */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        requireBcryptLength(request.password());
        String email = normalizeEmail(request.email());
        if (accounts.existsByEmailIgnoreCase(email)) throw new ConflictException("이미 가입된 이메일입니다.");
        EmailVerificationService.ConsumeResult verification = emailVerification.consume(email, request.code());
        if (verification != EmailVerificationService.ConsumeResult.CONSUMED) {
            throw new IllegalArgumentException(verification.message());
        }

        AuthAccount account = accounts.findByEmailIgnoreCase(withdrawnEmailHasher.hash(email)).orElse(null);
        boolean newAccount = account == null;
        String passwordHash = PASSWORD_ENCODER.encode(request.password());
        if (newAccount) {
            account = new AuthAccount(email, request.name().trim(), passwordHash);
        } else {
            account.reactivate(email, request.name().trim(), passwordHash);
        }
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일입니다.");
        }
        if (newAccount) credits.grantSignup(account.getId());
        return createSession(account);
    }

    /** 이메일과 BCrypt 비밀번호를 확인해 인증 실패 여부와 관계없이 같은 오류를 반환한다. */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        AuthAccount account = accounts.findByEmailIgnoreCase(email)
                .filter(AuthAccount::isActive)
                .filter(a -> PASSWORD_ENCODER.matches(request.password(), a.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다."));
        return createSession(account);
    }

    /** Access Token을 검증하고 현재 회원 상태와 권한을 DB에서 확인한다. */
    @Transactional(readOnly = true)
    public Optional<AuthAccount> findAccount(String token) {
        return tokens.verify(token, "access")
                .flatMap(jwt -> accounts.findById(jwt.userId())
                        .filter(a -> a.isActive() && a.getTokenVersion() == jwt.tokenVersion()));
    }

    @Transactional(readOnly = true)
    public Optional<UserResponse> findUser(String token) {
        return findAccount(token).map(this::userResponse);
    }

    /** Refresh Token을 원자적으로 교체한다. */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        JwtTokens.VerifiedToken jwt = tokens.verify(refreshToken, "refresh")
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 리프레시 토큰입니다."));
        AuthAccount account = accounts.lockById(jwt.userId()).filter(AuthAccount::isActive)
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 리프레시 토큰입니다."));
        Instant now = Instant.now();
        String replacement = tokens.refresh(account.getId(), jwt.sessionId(), now);
        int updated = sessions.rotate(jwt.sessionId(), account.getId(), InviteTokenService.hash(refreshToken),
                InviteTokenService.hash(replacement), now, now.plus(JwtTokens.REFRESH_LIFETIME));
        if (updated != 1) throw new UnauthorizedException("유효하지 않은 리프레시 토큰입니다.");
        return response(account, replacement, now);
    }

    /** 현재 Refresh Token을 폐기한다. 발급된 Access Token은 만료까지 유효하다. */
    @Transactional
    public void logout(String refreshToken) {
        JwtTokens.VerifiedToken jwt = tokens.verify(refreshToken, "refresh")
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 리프레시 토큰입니다."));
        if (sessions.revoke(jwt.sessionId(), jwt.userId(), InviteTokenService.hash(refreshToken), Instant.now()) != 1) {
            throw new UnauthorizedException("유효하지 않은 리프레시 토큰입니다.");
        }
    }

    @Transactional
    public void changePassword(String accessToken, PasswordChangeRequest request) {
        JwtTokens.VerifiedToken jwt = requireAccess(accessToken);
        AuthAccount account = lockCurrentAccount(jwt);
        requirePassword(account, request.currentPassword());
        requireBcryptLength(request.newPassword());
        account.changePassword(PASSWORD_ENCODER.encode(request.newPassword()));
        sessions.revokeAll(account.getId());
    }

    @Transactional
    public void withdraw(String accessToken, WithdrawalRequest request) {
        JwtTokens.VerifiedToken jwt = requireAccess(accessToken);
        accounts.findCompanyIdById(jwt.userId()).ifPresent(accounts::lockByCompanyId);
        AuthAccount account = lockCurrentAccount(jwt);
        requirePassword(account, request.currentPassword());
        if (account.getRole() == CurrentUser.Role.ADMIN) {
            throw new ForbiddenException("관리자 계정은 탈퇴할 수 없습니다.");
        }
        Instant now = Instant.now();
        if (account.getCompanyId() != null
                && examSessions.existsByCompanyIdAndCanceledAtIsNullAndEndsAtAfter(account.getCompanyId(), now)) {
            throw new ConflictException("예정 또는 진행 중인 회사 회차가 있어 탈퇴할 수 없습니다.");
        }
        if (account.getRole() == CurrentUser.Role.MANAGER) {
            throw new ForbiddenException("매니저는 먼저 위임하거나 관리자에게 교체를 요청해야 합니다.");
        }
        String anonymousEmail = withdrawnEmailHasher.hash(normalizeEmail(account.getEmail()));
        // 탈퇴 해시 행이 이미 따로 있는 계정(중복 생성된 기존 데이터)은 무작위 주소로 익명화한다
        if (accounts.existsByEmailIgnoreCase(anonymousEmail)) {
            anonymousEmail = "withdrawn+" + InviteTokenService.next() + "@invalid.local";
        }
        emailVerification.removeForEmail(account.getEmail());
        account.withdraw(anonymousEmail, PASSWORD_ENCODER.encode(InviteTokenService.next()), now);
        sessions.revokeAll(account.getId());
    }

    private JwtTokens.VerifiedToken requireAccess(String accessToken) {
        return tokens.verify(accessToken, "access")
                .orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
    }

    private AuthAccount lockCurrentAccount(JwtTokens.VerifiedToken jwt) {
        return accounts.lockById(jwt.userId())
                .filter(a -> a.isActive() && a.getTokenVersion() == jwt.tokenVersion())
                .orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
    }

    private void requirePassword(AuthAccount account, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72
                || !PASSWORD_ENCODER.matches(password, account.getPasswordHash())) {
            throw new UnauthorizedException("현재 비밀번호가 올바르지 않습니다.");
        }
    }

    private AuthResponse createSession(AuthAccount account) {
        Instant now = Instant.now();
        sessions.deleteExpired(now);
        String sessionId = InviteTokenService.next();
        String refreshToken = tokens.refresh(account.getId(), sessionId, now);
        sessions.save(new RefreshSession(sessionId, account.getId(), InviteTokenService.hash(refreshToken),
                now, now.plus(JwtTokens.REFRESH_LIFETIME)));
        return response(account, refreshToken, now);
    }

    private AuthResponse response(AuthAccount account, String refreshToken, Instant now) {
        return new AuthResponse(tokens.access(account.getId(), account.getTokenVersion(), now), refreshToken,
                JwtTokens.ACCESS_LIFETIME.toSeconds(), userResponse(account));
    }

    private UserResponse userResponse(AuthAccount account) {
        String companyName = account.getCompanyId() == null ? null : companies.findById(account.getCompanyId())
                .map(Company::getName)
                .orElseThrow(() -> new IllegalStateException("회원의 회사를 찾을 수 없습니다: " + account.getCompanyId()));
        return UserResponse.from(account, companyName);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireBcryptLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
    }
}
