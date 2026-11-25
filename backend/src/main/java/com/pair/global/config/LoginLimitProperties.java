package com.pair.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 실패 횟수 제한 (application.yml 의 pair.auth.login-limit). window 안의 실패 횟수로 판단한다.
 * perEmailAndIp: 한 곳에서 한 계정, perEmail: 여러 IP 에서 한 계정, perIp: 한 곳에서 여러 계정.
 * trustedProxyHops: 앞단 프록시 수. 운영은 nginx 1대라 1이고, HTTPS 프록시(Caddy 등)를 앞에 더 두면 2로 바꾼다.
 */
@ConfigurationProperties(prefix = "pair.auth.login-limit")
public record LoginLimitProperties(Duration window, int perEmailAndIp, int perEmail, int perIp, Integer trustedProxyHops) {

    public LoginLimitProperties {
        if (window == null || window.isZero() || window.isNegative()) window = Duration.ofMinutes(10);
        if (perEmailAndIp <= 0) perEmailAndIp = 5;
        if (perEmail <= 0) perEmail = 20;
        if (perIp <= 0) perIp = 50;
        if (trustedProxyHops == null || trustedProxyHops < 0) trustedProxyHops = 1;
    }
}
