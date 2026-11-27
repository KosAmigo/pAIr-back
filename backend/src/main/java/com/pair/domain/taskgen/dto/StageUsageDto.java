package com.pair.domain.taskgen.dto;

/**
 * 단계 호출 1번의 기록(report.json 의 stages): 단계, 모델, 추론 강도, 토큰, 걸린 초.
 * outputLimit: 출력 한도 끊김 처리. "" 없음, "lowered" 추론 강도를 낮춰 다시 불러 받음, "failed" 낮춰도 끊겨 단계 실패(토큰은 버린 것까지)
 * via: DIRECT(바로 호출) 또는 BATCH(배치, 가격 50%). waitedMs: 배치 창구에서 기다린 시간(ms, 바로 호출은 0).
 * directReason: 배치 작업인데 바로 호출로 부른 이유(wait 대기 초과, deadline 마감, length 출력 한도 끊김, failed 배치 실패). 아니면 ""
 */
public record StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds, String outputLimit,
        String via, long waitedMs, String directReason) {

    public static final String DIRECT = "DIRECT";
    public static final String BATCH = "BATCH";

    public StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds, String outputLimit) {
        this(stage, model, effort, inputTokens, outputTokens, seconds, outputLimit, DIRECT, 0, "");
    }

    public StageUsageDto(String stage, String model, String effort, long inputTokens, long outputTokens, long seconds) {
        this(stage, model, effort, inputTokens, outputTokens, seconds, "");
    }

    /** 실제 낸 돈의 비율: 배치는 입출력 가격의 50% */
    public double priceFactor() {
        return BATCH.equals(via) ? 0.5 : 1.0;
    }
}
