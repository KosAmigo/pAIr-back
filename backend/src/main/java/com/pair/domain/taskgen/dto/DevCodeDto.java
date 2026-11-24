package com.pair.domain.taskgen.dto;

import java.util.List;

/** 2'단계(DEV) 결과: 시작 코드(src/…)와 공개 테스트(test/…) */
public record DevCodeDto(List<DevDraftDto.FileDraft> files) {}
