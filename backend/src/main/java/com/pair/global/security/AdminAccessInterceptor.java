package com.pair.global.security;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.pair.global.error.ForbiddenException;
import com.pair.global.error.UnauthorizedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 관리자(ADMIN)만 쓸 수 있는 경로를 막는다. 로그인하지 않았으면 401, 관리자가 아니면 403.
 * 컨트롤러 메서드마다 검사를 빠뜨려도 새지 않게 한곳에서 막는다. 막을 경로는 AuthWebConfig 에서 정한다.
 */
@Component
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final CurrentUserResolver users;

    public AdminAccessInterceptor(CurrentUserResolver users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser user = users.resolve(request).orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
        if (!user.isAdmin()) throw new ForbiddenException("관리자 권한이 필요합니다.");
        return true;
    }
}
