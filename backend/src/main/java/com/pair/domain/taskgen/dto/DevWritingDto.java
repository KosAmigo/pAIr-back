package com.pair.domain.taskgen.dto;

import java.util.List;

/** 2단계(DEV) 작성 결과: 응시자에게 보이는 글. notes 는 NOTES.md 내용(없으면 빈 문자열) */
public record DevWritingDto(List<String> description, List<String> requirements, String notes) {}
