package com.pair.domain.exam.dto;

/** line: CSV 줄 번호(헤더 = 1) 또는 요청 목록 순서(1부터) */
public record RejectedParticipantDto(int line, String name, String email, String reason) {}
