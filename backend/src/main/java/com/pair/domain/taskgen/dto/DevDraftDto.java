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
        List<String> competencies) {

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
}
