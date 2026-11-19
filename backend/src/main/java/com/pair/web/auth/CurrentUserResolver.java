package com.pair.web.auth;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청에서 로그인 사용자를 꺼내는 경계. 지금은 개발용 {@link HeaderCurrentUserResolver} 하나뿐이다.
 * 팀원 1 이 Spring Security(JWT)를 붙이면 SecurityContext 에서 꺼내는 구현으로 바꾸면 되고, 컨트롤러는 그대로 둔다.
 */
public interface CurrentUserResolver {

    /** 로그인하지 않았으면 empty */
    Optional<CurrentUser> resolve(HttpServletRequest request);
}
