package com.pair.domain.exam.dto;

/** 입장 토큰이 가리키는 응시자. 감독관이 (회차, 대상자) 로 응시자 PC 연결을 찾을 때 쓴다 */
public record RelayIdentityDto(Long sessionId, Long participantId) {}
