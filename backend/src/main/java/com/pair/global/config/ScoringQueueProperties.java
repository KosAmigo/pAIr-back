package com.pair.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채점 큐 설정 (application.yml 의 pair.scoring-queue). 기본은 꺼짐이라 지금처럼 서버 안의 스레드 풀이 채점한다.
 * workers: 큐에서 꺼내 채점하는 스레드 수, maxAttempts: 한 작업을 시도하는 최대 횟수(첫 시도 포함),
 * retryDelay: 실패 뒤 다시 시도하기까지 기본 대기(시도 횟수만큼 곱해 늘린다),
 * staleAfter: 꺼낸 뒤 이 시간이 지나도 끝나지 않은 작업을 죽은 작업자의 것으로 보고 되살리는 기준.
 */
@ConfigurationProperties(prefix = "pair.scoring-queue")
public record ScoringQueueProperties(
        boolean enabled,
        int workers,
        int maxAttempts,
        Duration retryDelay,
        Duration staleAfter,
        String keyPrefix) {

    public ScoringQueueProperties {
        if (workers <= 0) workers = 4;
        if (maxAttempts <= 0) maxAttempts = 3;
        if (retryDelay == null || retryDelay.isNegative()) retryDelay = Duration.ofSeconds(30);
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) staleAfter = Duration.ofMinutes(15);
        if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "pair:scoring";
    }
}
