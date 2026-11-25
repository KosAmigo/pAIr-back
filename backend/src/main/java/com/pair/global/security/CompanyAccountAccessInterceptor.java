package com.pair.global.security;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.pair.global.error.ForbiddenException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 기업 계정(MANAGER·PROCTOR)은 자가진단과 크레딧을 새로 쓸 수 없다. 응시 시작, 롤백, 크레딧 구매·환불 같은 쓰기만 막고,
 * 예전 기록(응시, 크레딧 내역)을 보는 읽기는 허용한다. ADMIN 과 USER 는 막지 않는다.
 * 요청마다 현재 역할로 판단한다(매니저 위임·교체로 역할이 바뀌므로 로그인 때의 역할을 믿지 않는다).
 * /api/attempts/{id}/** 는 실전 응시(로그인 없음)도 쓰는 경로라 여기서 막지 않고 {@link AttemptOwnerInterceptor} 가 자가진단 응시에서만 막는다.
 */
@Component
public class CompanyAccountAccessInterceptor implements HandlerInterceptor {

    public static final String DENIED_MESSAGE = "기업 계정은 자가진단과 크레딧을 이용할 수 없습니다. 기존 기록은 조회만 할 수 있습니다.";

    private final CurrentUserResolver users;

    public CompanyAccountAccessInterceptor(CurrentUserResolver users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isRead(request.getMethod())) return true;
        users.resolve(request).filter(CurrentUser::isCompanyMember).ifPresent(user -> {
            throw new ForbiddenException(DENIED_MESSAGE);
        });
        return true;
    }

    /** 조회만 하는 요청인지 (데이터를 바꾸지 않는 메서드) */
    static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }
}
