package com.pair.domain.taskgen.dto;

/**
 * GET /api/admin/task-gen/cost-estimate: 만들기 전 예상 비용($).
 * perTaskUsd 는 같은 직군·제한시간·방식으로 최근 끝난 작업 비용의 중앙값(sampleSize 개). 기록이 모자라면 상한에서 어림한 값(basis DEFAULT).
 * perTaskCapUsd 는 과제 하나의 비용 상한(넘을 것 같으면 다시 만들지 않고 멈춤). total* 은 count 를 곱한 값
 */
public record TaskGenCostEstimateResponse(String track, int minutes, String mode, int count, double perTaskUsd, double perTaskCapUsd,
        double totalUsd, double totalCapUsd, int sampleSize, String basis) {}
