package com.pair.domain.exam.dto;

/** 행 단위 검증(형식·길이·중복)은 서비스가 한다. 잘못된 행 하나 때문에 전체를 거절하지 않고 이유와 함께 돌려준다 */
public record ParticipantInputDto(String name, String email) {}
