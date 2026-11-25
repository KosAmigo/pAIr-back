package com.pair.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인하지 않고 부를 수 있는 API 의 IP별 요청 수 제한 (application.yml 의 pair.ip-limit). window 안의 요청 수로 판단한다.
 * mail: 인증 메일 요청, signup: 가입(초대 가입 포함), inquiry: 기업 도입 문의.
 */
@ConfigurationProperties(prefix = "pair.ip-limit")
public record IpLimitProperties(Duration window, int mail, int signup, int inquiry) {

    public IpLimitProperties {
        if (window == null || window.isZero() || window.isNegative()) window = Duration.ofHours(1);
        if (mail <= 0) mail = 40;
        if (signup <= 0) signup = 60;
        if (inquiry <= 0) inquiry = 10;
    }
}
