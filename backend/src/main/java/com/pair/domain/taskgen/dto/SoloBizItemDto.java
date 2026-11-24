package com.pair.domain.taskgen.dto;

/** 응시자 AI 가 낸 문항 하나의 답. no 는 문항 번호(1부터) */
public record SoloBizItemDto(Integer no, String answer) {}
