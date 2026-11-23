package com.pair.global.config;

import com.pair.global.security.AttemptOwnerInterceptor;
import com.pair.global.security.CurrentUserArgumentResolver;
import com.pair.global.security.OnboardingAccessInterceptor;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;
    private final AttemptOwnerInterceptor attemptOwner;
    private final OnboardingAccessInterceptor onboardingAccess;

    public AuthWebConfig(CurrentUserArgumentResolver currentUser, AttemptOwnerInterceptor attemptOwner,
            OnboardingAccessInterceptor onboardingAccess) {
        this.currentUser = currentUser;
        this.attemptOwner = attemptOwner;
        this.onboardingAccess = onboardingAccess;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    /** 응시 하나를 다루는 모든 요청(/api/attempts/{id}/**)은 그 응시의 주인만 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(onboardingAccess).addPathPatterns("/api/**");
        registry.addInterceptor(attemptOwner).addPathPatterns("/api/attempts/{id}/**");
    }
}
