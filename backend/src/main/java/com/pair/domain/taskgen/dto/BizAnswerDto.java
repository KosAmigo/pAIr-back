package com.pair.domain.taskgen.dto;

import java.util.List;

/** 3단계(BIZ) 정답 쪽: 문항(인정 답·검산식·흔한 오답 포함), 채점표, 이 과제 맞춤 후속 질문 1개 */
public record BizAnswerDto(String checklist, List<BizDraftDto.QuestionDraft> questions, String taskFollowUp) {}
