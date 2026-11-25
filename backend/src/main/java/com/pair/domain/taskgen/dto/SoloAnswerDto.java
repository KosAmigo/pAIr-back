package com.pair.domain.taskgen.dto;

import java.util.List;

/** "AI 혼자 바로 풀림" 검사에서 AI 가 돌려주는 답: 고친 src 파일의 전체 내용. JSON Schema(strict 구조화 출력)를 이 record 에서 만든다 */
public record SoloAnswerDto(List<DevDraftDto.FileDraft> files) {}
