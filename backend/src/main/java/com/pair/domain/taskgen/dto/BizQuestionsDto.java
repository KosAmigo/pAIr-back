package com.pair.domain.taskgen.dto;

import java.util.List;

/** 3단계(BIZ) 문항 글: 결함을 모르는 단계가 쓴 문항 문구와 보기(숫자 또는 짧은 결론만). id 는 사례 name(Q번호) */
public record BizQuestionsDto(List<Question> questions) {

    public record Question(String id, String content, List<String> choices) {}
}
