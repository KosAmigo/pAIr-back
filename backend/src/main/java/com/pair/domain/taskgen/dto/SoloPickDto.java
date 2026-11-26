package com.pair.domain.taskgen.dto;

import java.util.List;

/** AI 단독 풀이의 파일 고르기 답: 붙일 파일 경로(중요한 순서). JSON Schema(strict 구조화 출력)를 이 record 에서 만든다 */
public record SoloPickDto(List<String> files) {}
