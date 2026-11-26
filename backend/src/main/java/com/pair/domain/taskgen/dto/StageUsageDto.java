package com.pair.domain.taskgen.dto;

/**
 * 단계 호출 1번의 기록(report.json 의 stages): 단계, 모델, 추론 강도, 토큰, 걸린 초.
 * outputLimit: 출력 한도 끊김 처리. "" 없음, "lowered" 추론 강도를 낮춰 다시 불러 받음, "failed" 낮춰도 끊겨 단계 실패(토큰은 버린 것까지)
 */
public record StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds, String outputLimit) {

    public StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds) {
        this(stage, model, effort, inputTokens, outputTokens, seconds, "");
    }
}
