package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * "AI 답을 그대로 내면 함정에 걸림" 검사에서 응시자 AI 가 낸 답. 문항 번호(no, 1부터)를 붙인 목록이다.
 * 위치가 아니라 번호로 문항에 맞추므로, AI 가 기획안 같은 글을 앞에 섞어도 답이 밀리지 않는다.
 * 객관식은 보기 번호, 단답형은 숫자
 */
public record SoloBizAnswerDto(List<SoloBizItemDto> answers) {}
