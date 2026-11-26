package com.pair.domain.taskgen.dto;

/** 단계 호출 1번의 기록(report.json 의 stages): 단계, 모델, 추론 강도, 토큰, 걸린 초 */
public record StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds) {}
