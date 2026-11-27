package com.pair.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 과제 생성기 전용 코드 실행 설정 (pair.taskgen.run.*). 응시용 실행 장치(pair.run.*)와 따로 돈다.
 * maxConcurrent: 동시에 돌 코드 실행 수(기본 CPU 수, 넘으면 공정한 줄에서 기다림), timeoutSeconds: 실행 1번 시간 제한(기본 응시용 pair.run.timeout-seconds와 같음),
 * heapMb: 테스트 JVM 하나의 최대 힙(-Xmx, 기본 256).
 */
@ConfigurationProperties(prefix = "pair.taskgen.run")
public record TaskGenRunProperties(Integer maxConcurrent, Integer timeoutSeconds, Integer heapMb) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public TaskGenRunProperties {
        if (maxConcurrent == null || maxConcurrent < 1) maxConcurrent = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (heapMb == null || heapMb < 64) heapMb = 256;
    }

    /** timeoutSeconds 가 비었으면 응시용 설정을 쓴 값 */
    public int timeoutOr(int defaultSeconds) {
        return timeoutSeconds == null || timeoutSeconds < 1 ? defaultSeconds : timeoutSeconds;
    }
}
