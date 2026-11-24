package com.pair.domain.taskgen.dto;

import java.util.List;

/** 2단계(BIZ) 작성 결과: 설명, 요구사항, 문서(쪽별 글). CSV 는 숫자표에서 코드가 만든다 */
public record BizWritingDto(List<String> description, List<String> requirements, List<BizDraftDto.DocDraft> documents) {}
