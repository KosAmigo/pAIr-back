package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * AI 가 만들어 주는 개발 과제 (PoC 스키마 + code, summary, category).
 * 이 record 에서 JSON Schema(strict 구조화 출력)를 만든다.
 */
public record DevDraftDto(
        String title,
        String summary,
        String code,
        String category,
        List<String> description,
        List<String> requirements,
        String answer,
        List<FileDraft> files,
        List<FileDraft> hiddenTests,
        List<FileDraft> solution,
        String difficulty,
        List<String> competencies,
        List<String> trapKeywords,
        List<WrongFix> wrongFixes,
        String taskFollowUp) {

    /** 새 필드(함정 핵심어, 그럴듯한 오답, 맞춤 후속 질문) 없이 만든다(테스트·옛 코드용) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution, String difficulty, List<String> competencies) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, difficulty,
                competencies, null, null, null);
    }

    /** 역량 없이 만든다(테스트·옛 코드용) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution, String difficulty) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, difficulty, null);
    }

    /** 난이도 없이 만든다(테스트·옛 코드용) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, null, null);
    }

    public record FileDraft(String path, String content) {}

    /** AI 가 그럴듯하게 제안할 법하지만 틀린 수정. files 는 바뀐 src 파일의 전체 내용, reason 은 왜 틀렸는지 */
    public record WrongFix(String reason, List<FileDraft> files) {}
}
