package com.pair.domain.taskgen.dto;

import java.util.List;

/** 4단계(DEV) 정답 쪽: 숨은 테스트, 정답, 다른 풀이, 오답, 정답 설명. 응시자에게 보이는 글은 쓰지 않는다 */
public record DevAnswerDto(
        String answer,
        List<DevDraftDto.FileDraft> hiddenTests,
        List<DevDraftDto.FileDraft> solution,
        List<DevDraftDto.AltSolution> altSolutions,
        List<DevDraftDto.WrongFix> wrongFixes) {}
