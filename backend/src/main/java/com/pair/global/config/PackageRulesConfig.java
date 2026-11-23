package com.pair.global.config;

import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서버가 켜질 때 패키지 구조·이름 규칙(PackageRules)을 검사한다. 어긴 클래스가 있으면 서버가 뜨지 않는다.
 * IntelliJ 에서 PairApplication 을 바로 실행해도 돈다. 빈을 만들기 전에 돌아 DB 연결보다 먼저 멈춘다.
 * 운영 서버에서는 PACKAGE_RULES_ENABLED=false 로 끈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "package-rules.enabled", havingValue = "true", matchIfMissing = true)
public class PackageRulesConfig {

    @Bean
    static BeanFactoryPostProcessor packageRulesCheck() {
        return beanFactory -> {
            List<String> violations = new PackageRules().violations();
            if (!violations.isEmpty()) {
                throw new IllegalStateException("패키지 구조 규칙을 어긴 클래스가 있습니다 (pAIr-docs 설계/백엔드_패키지_구조.md, "
                        + "끄려면 PACKAGE_RULES_ENABLED=false):\n  - " + String.join("\n  - ", violations));
            }
        };
    }
}
