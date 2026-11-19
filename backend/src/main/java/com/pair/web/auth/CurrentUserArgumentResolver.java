package com.pair.web.auth;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/** 컨트롤러 파라미터 CurrentUser 를 채운다. 로그인하지 않았으면 401 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserResolver resolver;

    public CurrentUserArgumentResolver(CurrentUserResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
            WebDataBinderFactory binders) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        return resolver.resolve(http).orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
    }
}
