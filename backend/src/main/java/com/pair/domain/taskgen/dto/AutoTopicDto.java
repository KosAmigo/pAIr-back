package com.pair.domain.taskgen.dto;

import java.util.List;

/** 자동 생성의 주제 하나(미리보기 응답·auto 요청의 topics). trapType 은 "R4 적용 순서"처럼 유형 번호와 이름 */
public record AutoTopicDto(String situation, String trap, String trapType, List<String> competencies) {}
