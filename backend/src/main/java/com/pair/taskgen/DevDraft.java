package com.pair.taskgen;

import java.util.List;

/**
 * AI 가 만들어 주는 개발 과제 (PoC 스키마 + code, summary, category).
 * 이 record 에서 JSON Schema(strict 구조화 출력)를 만든다.
 */
public record DevDraft(
        String title,
        String summary,
        String code,
        String category,
        List<String> description,
        List<String> requirements,
        String answer,
        List<FileDraft> files,
        List<FileDraft> hiddenTests,
        List<FileDraft> solution) {

    public record FileDraft(String path, String content) {}
}
