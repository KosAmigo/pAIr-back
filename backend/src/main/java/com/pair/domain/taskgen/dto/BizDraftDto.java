package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * AI 가 만들어 주는 비개발 과제 (PoC 스키마 + code, summary, category).
 * 문서는 쪽별 글로 받아 서버가 PDF 로 만든다. CSV 는 글로 받는다.
 * 문항마다 근거 문서(sources)를 2개 이상 적는다.
 */
public record BizDraftDto(
        String title,
        String summary,
        String code,
        String category,
        List<String> description,
        List<String> requirements,
        List<String> followUps,
        List<DocDraft> documents,
        List<DevDraftDto.FileDraft> csvFiles,
        String checklist,
        Trap trap,
        List<QuestionDraft> questions,
        String difficulty,
        List<String> competencies,
        List<String> trapKeywords) {

    /** 함정 핵심어 없이 만든다(테스트·옛 코드용) */
    public BizDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, List<String> followUps, List<DocDraft> documents,
            List<DevDraftDto.FileDraft> csvFiles, String checklist, Trap trap, List<QuestionDraft> questions,
            String difficulty, List<String> competencies) {
        this(title, summary, code, category, description, requirements, followUps, documents, csvFiles, checklist, trap, questions, difficulty, competencies, null);
    }

    /** 역량 없이 만든다(테스트·옛 코드용) */
    public BizDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, List<String> followUps, List<DocDraft> documents,
            List<DevDraftDto.FileDraft> csvFiles, String checklist, Trap trap, List<QuestionDraft> questions,
            String difficulty) {
        this(title, summary, code, category, description, requirements, followUps, documents, csvFiles, checklist, trap, questions, difficulty, null);
    }

    /** 난이도 없이 만든다(테스트·옛 코드용) */
    public BizDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, List<String> followUps, List<DocDraft> documents,
            List<DevDraftDto.FileDraft> csvFiles, String checklist, Trap trap, List<QuestionDraft> questions) {
        this(title, summary, code, category, description, requirements, followUps, documents, csvFiles, checklist, trap, questions, null, null);
    }

    /** path: 자료/이름.pdf, pages: 쪽별 글 */
    public record DocDraft(String path, List<String> pages) {}

    public record Trap(String description, String evidence) {}

    public enum Type { SINGLE, SHORT }

    public record Choice(String content, boolean correct) {}

    /** path: 근거 문서 경로, pages: 쪽 번호(예: "1,2") */
    public record Source(String path, String pages) {}

    /** AI 가 흔히 낼 법한 틀린 값(숫자)과 그 이유 */
    public record WrongAnswer(String value, String reason) {}

    /** commonWrongAnswers: 단답형만 1~2개(객관식은 빈 목록) */
    public record QuestionDraft(Type type, String content, List<Choice> choices, List<String> accepted, String calc,
            List<Source> sources, List<WrongAnswer> commonWrongAnswers) {

        /** 흔한 오답 없이 만든다(테스트·옛 코드용) */
        public QuestionDraft(Type type, String content, List<Choice> choices, List<String> accepted, String calc,
                List<Source> sources) {
            this(type, content, choices, accepted, calc, sources, null);
        }
    }
}
