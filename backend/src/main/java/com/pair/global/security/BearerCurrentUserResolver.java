package com.pair.global.security;

import com.pair.domain.auth.service.AuthService;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** Authorization bearer 토큰을 검증해 백엔드가 신뢰할 현재 회원 정보를 만든다. */
@Component
@ConditionalOnProperty(name = "pair.auth.mode", havingValue = "bearer", matchIfMissing = true)
public class BearerCurrentUserResolver implements CurrentUserResolver {

    private final AuthService auth;

    public BearerCurrentUserResolver(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public Optional<CurrentUser> resolve(HttpServletRequest request) {
        return ResolvedCurrentUser.resolve(request, () -> {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
            String token = authorization.substring(7).trim();
            return auth.findAccount(token).map(a -> new CurrentUser(a.getId(), a.getRole(), a.getCompanyId(),
                    a.isEmailVerified(), a.isPasswordChangeRequired()));
        });
    }
}
