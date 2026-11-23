package com.pair.web.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Unfinished corporate accounts may only use account activation endpoints. */
@Component
public class OnboardingAccessInterceptor implements HandlerInterceptor {

    private final CurrentUserResolver users;

    public OnboardingAccessInterceptor(CurrentUserResolver users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (allowedWhileOnboarding(request.getRequestURI())) return true;
        users.resolve(request).ifPresent(user -> {
            if (!user.emailVerified() || user.passwordChangeRequired()) {
                throw new UnauthorizedException("이메일 인증과 비밀번호 변경을 완료해 주세요.");
            }
        });
        return true;
    }

    private boolean allowedWhileOnboarding(String path) {
        return path.equals("/api/auth/me")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/password")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout")
                || path.startsWith("/api/auth/onboarding/");
    }
}
