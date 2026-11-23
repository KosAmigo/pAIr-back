package com.pair.web.auth;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 개발용: 프론트 목업 로그인이 보내는 헤더를 그대로 믿는다. 운영에서 쓰면 누구나 아무 회사로 행세할 수 있다.
 * pair.auth.mode=header 일 때만 사용한다. 실제 서비스 기본 인증은 bearer 토큰이다.
 */
@Component
@ConditionalOnProperty(name = "pair.auth.mode", havingValue = "header")
public class HeaderCurrentUserResolver implements CurrentUserResolver {

    public static final String USER_ID = "X-Pair-User-Id";
    public static final String ROLE = "X-Pair-Role";
    public static final String COMPANY_ID = "X-Pair-Company-Id";

    @Override
    public Optional<CurrentUser> resolve(HttpServletRequest request) {
        return ResolvedCurrentUser.resolve(request, () -> {
            String userId = request.getHeader(USER_ID);
            String role = request.getHeader(ROLE);
            if (userId == null || userId.isBlank() || role == null || role.isBlank()) return Optional.empty();
            try {
                String company = request.getHeader(COMPANY_ID);
                return Optional.of(new CurrentUser(Long.valueOf(userId.trim()), CurrentUser.Role.valueOf(role.trim().toUpperCase()),
                        company == null || company.isBlank() ? null : Long.valueOf(company.trim())));
            } catch (IllegalArgumentException e) {
                throw new UnauthorizedException("로그인 정보가 올바르지 않습니다.");
            }
        });
    }
}
