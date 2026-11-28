package com.pair.domain.taskgen.dto;

import java.util.List;

/** 검수의 "다른 풀이" 점검 결과: 공식 풀이와 구조가 다른 올바른 풀이(바뀐 src 파일 전체). 서버가 숨은 테스트로 실제 실행한다 */
public record AltSolutionDto(List<DevDraftDto.FileDraft> files) {}
