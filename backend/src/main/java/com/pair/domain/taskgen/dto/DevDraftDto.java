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
        String taskFollowUp,
        List<AltSolution> altSolutions,
        List<JudgmentPoint> judgmentPoints,
        List<HiddenFlaw> hiddenFlaws) {

    /** 판단 지점·숨은 결함(E2, E3) 없이 만든다(옛 형식 호환: 없으면 검사가 경고만 한다) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution, String difficulty, List<String> competencies, List<String> trapKeywords,
            List<WrongFix> wrongFixes, String taskFollowUp, List<AltSolution> altSolutions) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, difficulty,
                competencies, trapKeywords, wrongFixes, taskFollowUp, altSolutions, null, null);
    }

    /** 다른 방식의 올바른 풀이(altSolutions) 없이 만든다(테스트·옛 형식 호환용) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution, String difficulty, List<String> competencies, List<String> trapKeywords,
            List<WrongFix> wrongFixes, String taskFollowUp) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, difficulty,
                competencies, trapKeywords, wrongFixes, taskFollowUp, null);
    }

    /** 새 필드(함정 핵심어, 그럴듯한 오답, 맞춤 후속 질문) 없이 만든다(테스트·옛 코드용) */
    public DevDraftDto(String title, String summary, String code, String category, List<String> description,
            List<String> requirements, String answer, List<FileDraft> files, List<FileDraft> hiddenTests,
            List<FileDraft> solution, String difficulty, List<String> competencies) {
        this(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution, difficulty,
                competencies, null, null, null, null);
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

    /**
     * 정답과 구조(락 위치, 자료구조, 메서드 분리 등)는 다르지만 요구사항은 모두 지키는 올바른 풀이.
     * files 는 바뀐 src 파일의 전체 내용, note 는 정답과 어떻게 다른지 한 줄. 숨은+공개 테스트를 모두 통과해야 한다
     */
    public record AltSolution(String note, List<FileDraft> files) {}

    /**
     * E2 판단 지점: 사람이 정해야 하는 모호한 요구·트레이드오프. question 은 무엇을 정해야 하나, options 는 고를 수 있는 선택지,
     * basis 는 판단 근거가 되는 문장(응시자에게 보이는 자료에 있는 그대로),
     * evidence 는 선택지를 뒷받침하는 사실 문장(보이는 자료에서 복사한 문장이며 "파일명 N쪽" 같은 위치만 쓰면 warn. 한 문장이 여러 선택지의 근거여도 됨, E2 M4). DEV·BIZ 가 함께 쓴다
     */
    public record JudgmentPoint(String question, List<String> options, String basis, List<String> evidence) {

        /** 선택지별 근거 없이 만든다(테스트·옛 형식 호환) */
        public JudgmentPoint(String question, List<String> options, String basis) {
            this(question, options, basis, null);
        }
    }

    /**
     * E3 숨은 결함: AI 기본 답이 놓치기 쉬운 곳. description 은 결함 설명, verifyWith 는 자료·테스트 어디와 대조하면 보이는지,
     * caughtBy 는 DEV 에서 이 결함을 잡는 숨은 테스트 메서드 이름, BIZ 에서는 답·결론이 달라지는 문항("Q2") 또는 "결론"
     */
    public record HiddenFlaw(String description, String verifyWith, String caughtBy) {}
}
