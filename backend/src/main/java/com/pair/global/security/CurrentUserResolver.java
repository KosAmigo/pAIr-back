package com.pair.global.security;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청에서 로그인 사용자를 꺼내는 경계. 개발 테스트는 헤더, 실제 요청은 bearer 토큰으로 확인한다.
 */
public interface CurrentUserResolver {

    /** 로그인하지 않았으면 empty */
    Optional<CurrentUser> resolve(HttpServletRequest request);
}
