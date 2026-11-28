package com.pair.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 과제 생성 배치 창구 설정 (pair.taskgen.batch.*, 설계 7장).
 * waitGenerate: 생성 단계(설계도·작성·코드·문항 글·정답 쪽·관련 자료) 한 호출이 배치에서 기다릴 수 있는 최대 시간(기본 20분).
 * waitReview: 관문·점검 단계(기본 10분). 넘으면 그 호출만 배치에서 빼고 바로 호출로 보낸다.
 * hold: 모아 둔 요청 중 가장 오래된 것이 이만큼 기다리면 보낸다(기본 60초). poll: 보낸 배치 상태를 보는 간격(기본 30초).
 * directStepSeconds: 마감 계산에 쓰는 '바로 호출 한 단계 평균 시간'(기본 90초). margin: 마감 여유(기본 15분).
 */
@ConfigurationProperties(prefix = "pair.taskgen.batch")
public record TaskGenBatchProperties(Duration waitGenerate, Duration waitReview, Duration hold, Duration poll, Integer directStepSeconds, Duration margin) {

    public TaskGenBatchProperties {
        if (waitGenerate == null) waitGenerate = Duration.ofMinutes(20);
        if (waitReview == null) waitReview = Duration.ofMinutes(10);
        if (hold == null) hold = Duration.ofSeconds(60);
        if (poll == null) poll = Duration.ofSeconds(30);
        if (directStepSeconds == null || directStepSeconds < 1) directStepSeconds = 90;
        if (margin == null) margin = Duration.ofMinutes(15);
    }

    public static TaskGenBatchProperties defaults() {
        return new TaskGenBatchProperties(null, null, null, null, null, null);
    }
}
