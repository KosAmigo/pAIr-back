package com.pair.domain.taskgen.dto;

import java.util.List;

/** "AI 답을 그대로 내면 함정에 걸림" 검사에서 응시자 AI 가 낸 답. 문항 순서대로 객관식은 보기 번호, 단답형은 숫자 */
public record SoloBizAnswerDto(List<String> answers) {}
