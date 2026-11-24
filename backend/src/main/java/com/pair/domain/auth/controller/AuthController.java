package com.pair.domain.auth.controller;

import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.dto.EmailVerificationRequest;
import com.pair.domain.auth.dto.EmailVerificationResponse;
import com.pair.domain.auth.dto.LoginRequest;
import com.pair.domain.auth.dto.OnboardingEmailVerificationRequest;
import com.pair.domain.auth.dto.PasswordChangeRequest;
import com.pair.domain.auth.dto.RefreshRequest;
import com.pair.domain.auth.dto.SignupRequest;
import com.pair.domain.auth.dto.UserResponse;
import com.pair.domain.auth.dto.WithdrawalRequest;
import com.pair.domain.auth.service.AuthService;
import com.pair.global.error.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** 회원가입·로그인·현재 계정 조회·로그아웃 HTTP API를 제공한다. */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** 가입 이메일로 인증 코드를 발송한다. */
    @PostMapping("/email-verifications")
    public ResponseEntity<EmailVerificationResponse> requestEmailVerification(
            @Valid @RequestBody EmailVerificationRequest request) {
        auth.requestEmailVerification(request.email());
        return ResponseEntity.accepted().body(new EmailVerificationResponse("인증 코드를 이메일로 보냈습니다."));
    }

    @PostMapping("/onboarding/email-verifications")
    public ResponseEntity<EmailVerificationResponse> requestOnboardingEmailVerification(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        auth.requestOnboardingEmailVerification(bearerToken(authorization));
        return ResponseEntity.accepted().body(new EmailVerificationResponse("인증 코드를 이메일로 보냈습니다."));
    }

    @PostMapping("/onboarding/email-verification")
    public ResponseEntity<Void> verifyOnboardingEmail(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody OnboardingEmailVerificationRequest request) {
        auth.verifyOnboardingEmail(bearerToken(authorization), request.code());
        return ResponseEntity.noContent().build();
    }

    /** 이메일 인증 코드를 확인한 뒤 일반 회원 계정을 만들고 로그인 토큰을 반환한다. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(auth.signup(request));
    }

    /** 이메일과 비밀번호를 확인하고 새 로그인 세션을 발급한다. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(request));
    }

    /** bearer 토큰에 연결된 현재 회원 정보를 반환한다. */
    @GetMapping("/me")
    public UserResponse me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        return auth.findUser(bearerToken(authorization))
                .orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
    }

    /** Refresh Token을 교체하고 새로운 토큰 쌍을 반환한다. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.refresh(request.refreshToken()));
    }

    /** Refresh Token과 로그인 세션을 폐기한다. 그 세션의 Access Token도 바로 무효가 되고 다른 기기는 그대로다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody PasswordChangeRequest request) {
        auth.changePassword(bearerToken(authorization), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/withdrawal")
    public ResponseEntity<Void> withdraw(@RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody WithdrawalRequest request) {
        auth.withdraw(bearerToken(authorization), request);
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7).trim();
    }
}
