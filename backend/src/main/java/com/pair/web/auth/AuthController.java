package com.pair.web.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ResponseEntity<AuthDtos.EmailVerificationResponse> requestEmailVerification(
            @Valid @RequestBody AuthDtos.EmailVerificationRequest request) {
        auth.requestEmailVerification(request.email());
        return ResponseEntity.accepted().body(new AuthDtos.EmailVerificationResponse("인증 코드를 이메일로 보냈습니다."));
    }

    /** 이메일 인증 코드를 확인한 뒤 일반 회원 계정을 만들고 로그인 토큰을 반환한다. */
    @PostMapping("/signup")
    public ResponseEntity<AuthDtos.AuthResponse> signup(@Valid @RequestBody AuthDtos.SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.signup(request));
    }

    /** 이메일과 비밀번호를 확인하고 새 로그인 세션을 발급한다. */
    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.Credentials request) {
        return auth.login(request);
    }

    /** bearer 토큰에 연결된 현재 회원 정보를 반환한다. */
    @GetMapping("/me")
    public AuthDtos.UserView me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        return auth.findAccount(bearerToken(authorization)).map(AuthDtos.UserView::from)
                .orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
    }

    /** 현재 bearer 세션을 폐기해 해당 토큰을 무효화한다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        auth.logout(bearerToken(authorization));
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7).trim();
    }
}
