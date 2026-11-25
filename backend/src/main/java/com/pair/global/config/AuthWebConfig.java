package com.pair.global.config;

import com.pair.global.security.AdminAccessInterceptor;
import com.pair.global.security.AttemptOwnerInterceptor;
import com.pair.global.security.CompanyAccountAccessInterceptor;
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
    private final CompanyAccountAccessInterceptor companyAccountAccess;
    private final AdminAccessInterceptor adminAccess;

    public AuthWebConfig(CurrentUserArgumentResolver currentUser, AttemptOwnerInterceptor attemptOwner,
            OnboardingAccessInterceptor onboardingAccess, CompanyAccountAccessInterceptor companyAccountAccess,
            AdminAccessInterceptor adminAccess) {
        this.currentUser = currentUser;
        this.attemptOwner = attemptOwner;
        this.onboardingAccess = onboardingAccess;
        this.companyAccountAccess = companyAccountAccess;
        this.adminAccess = adminAccess;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    /** 응시 하나를 다루는 모든 요청(/api/attempts/{id}/**)은 그 응시의 주인만 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(onboardingAccess).addPathPatterns("/api/**");
        // 채점 툴은 관리자만 (응시 기록 조회, 채점 프롬프트 변경, AI 비용이 드는 실험 채점)
        registry.addInterceptor(adminAccess).addPathPatterns("/api/grader/**");
        // 자가진단 시작·롤백과 크레딧 구매·환불은 기업 계정이 쓸 수 없다 (조회는 허용)
        registry.addInterceptor(companyAccountAccess).addPathPatterns("/api/attempts", "/api/credits/**", "/api/checkpoints/**");
        registry.addInterceptor(attemptOwner).addPathPatterns("/api/attempts/{id}/**");
    }
}
