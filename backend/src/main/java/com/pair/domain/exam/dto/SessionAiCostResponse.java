package com.pair.domain.exam.dto;

import java.util.List;

/**
 * 회차 AI 비용(관리자 전용). 금액은 설정 단가(pair.pricing, $/100만 토큰)로 계산한 추정치(USD)다.
 * 캐시 입력 토큰도 일반 입력 단가로 계산하므로 실제 청구액보다 같거나 크다(상한).
 * 단가가 없는 모델은 usd 가 null 이고 unpricedModels 에 이름이 들어가며, 합계에서 빠진다.
 *
 * @param attempts 회차의 응시 수(대상자 × 과제)
 * @param perAttemptUsd 응시 1건당 평균 비용. 응시가 없으면 null
 */
public record SessionAiCostResponse(long sessionId, long attempts, double totalUsd, Double perAttemptUsd,
                                    Part chat, Part scoring, List<String> unpricedModels) {

    /** 용도(AI 채팅, 채점)별 합계 */
    public record Part(long calls, long inputTokens, long outputTokens, long cachedTokens, double usd, List<ModelLine> models) {}

    /** 모델 하나의 합계 */
    public record ModelLine(String model, long calls, long inputTokens, long outputTokens, long cachedTokens, Double usd) {}
}
