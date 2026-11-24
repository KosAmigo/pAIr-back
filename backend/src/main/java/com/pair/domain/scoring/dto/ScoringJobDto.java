package com.pair.domain.scoring.dto;

/**
 * 채점 큐의 작업 하나. 채점에 필요한 정보는 응시 ID 뿐이고 나머지는 DB 에서 읽는다.
 * messageId 는 큐가 붙인 번호(처리가 끝났다고 알릴 때 쓴다), attempt 는 몇 번째 시도인지(1부터).
 */
public record ScoringJobDto(String messageId, String attemptId, int attempt) {}
