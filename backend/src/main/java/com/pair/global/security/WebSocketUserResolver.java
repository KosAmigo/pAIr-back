package com.pair.global.security;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pair.domain.auth.service.AuthService;

/**
 * WebSocket 메시지에 실린 로그인 정보(HTTP 헤더와 같은 이름·값)에서 로그인 사용자를 꺼낸다.
 * 브라우저 WebSocket 은 연결할 때 Authorization 헤더를 못 붙이므로, 프론트가 첫 메시지(watch)에 헤더 값을 실어 보낸다.
 * REST 와 같은 규칙을 쓴다: 기본(bearer)은 Authorization 의 액세스 토큰을 검증하고, 개발용(header)은 X-Pair-* 헤더를 믿는다.
 * 키는 대소문자를 가리지 않는다. 잘못된 값은 로그인하지 않은 것으로 본다.
 */
@Component
public class WebSocketUserResolver {

    private final AuthService auth;
    private final boolean headerMode;

    public WebSocketUserResolver(AuthService auth, @Value("${pair.auth.mode:bearer}") String mode) {
        this.auth = auth;
        this.headerMode = "header".equalsIgnoreCase(mode);
    }

    /** 로그인하지 않았거나 값이 올바르지 않으면 empty */
    public Optional<CurrentUser> resolve(Map<String, String> headers) {
        return headerMode ? fromDevHeaders(headers) : fromBearer(headers);
    }

    private Optional<CurrentUser> fromBearer(Map<String, String> headers) {
        String authorization = headers.get("authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        return auth.findAccount(authorization.substring(7).trim()).map(a -> new CurrentUser(a.getId(), a.getRole(), a.getCompanyId(),
                a.isEmailVerified(), a.isPasswordChangeRequired()));
    }

    private Optional<CurrentUser> fromDevHeaders(Map<String, String> headers) {
        String userId = headers.get(HeaderCurrentUserResolver.USER_ID.toLowerCase());
        String role = headers.get(HeaderCurrentUserResolver.ROLE.toLowerCase());
        if (userId == null || userId.isBlank() || role == null || role.isBlank()) return Optional.empty();
        try {
            String company = headers.get(HeaderCurrentUserResolver.COMPANY_ID.toLowerCase());
            return Optional.of(new CurrentUser(Long.valueOf(userId.trim()), CurrentUser.Role.valueOf(role.trim().toUpperCase()),
                    company == null || company.isBlank() ? null : Long.valueOf(company.trim())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
