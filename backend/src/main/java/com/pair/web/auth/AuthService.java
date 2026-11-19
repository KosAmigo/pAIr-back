package com.pair.web.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.service.InviteTokens;

/** 계정 생성·비밀번호 검증과 해시된 bearer 세션의 발급, 확인, 폐기를 처리한다. */
@Service
public class AuthService {

    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final AuthAccountRepository accounts;
    private final AuthSessionRepository sessions;

    public AuthService(AuthAccountRepository accounts, AuthSessionRepository sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    /** 일반 회원을 생성하고 로그인 상태로 사용할 세션 토큰을 발급한다. */
    @Transactional
    public AuthDtos.AuthResponse signup(AuthDtos.SignupRequest request) {
        requireBcryptLength(request.password());
        String email = normalizeEmail(request.email());
        if (accounts.existsByEmailIgnoreCase(email)) throw new ConflictException("이미 가입된 이메일입니다.");

        AuthAccount account;
        try {
            account = accounts.saveAndFlush(new AuthAccount(email, request.name().trim(), PASSWORD_ENCODER.encode(request.password())));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일입니다.");
        }
        return createSession(account);
    }

    /** 이메일과 BCrypt 비밀번호를 확인해 인증 실패 여부와 관계없이 같은 오류를 반환한다. */
    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.Credentials request) {
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

    /** 원문 bearer 토큰의 해시로 만료 전 세션과 회원을 확인한다. */
    @Transactional(readOnly = true)
    public Optional<AuthAccount> findAccount(String token) {
        if (token == null || !InviteTokens.looksValid(token)) return Optional.empty();
        return sessions.findByTokenHashAndExpiresAtAfter(InviteTokens.hash(token), Instant.now()).map(AuthSession::getAccount);
    }

    /** 토큰 해시로 현재 로그인 세션을 폐기한다. */
    @Transactional
    public void logout(String token) {
        if (token != null && InviteTokens.looksValid(token)) sessions.revoke(InviteTokens.hash(token));
    }

    private AuthDtos.AuthResponse createSession(AuthAccount account) {
        Instant now = Instant.now();
        sessions.deleteExpired(now);
        String token = InviteTokens.next();
        sessions.save(new AuthSession(InviteTokens.hash(token), account, now, now.plus(SESSION_LIFETIME)));
        return new AuthDtos.AuthResponse(token, AuthDtos.UserView.from(account));
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
