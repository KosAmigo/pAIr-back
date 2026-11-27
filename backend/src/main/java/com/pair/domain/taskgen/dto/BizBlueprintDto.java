package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * BIZ 설계도 단계가 모델에게 받는 모양(2026-11-26 g6: 직군별 설계도). DEV 칸(공개 API·예·오답 방식)이 없다.
 * 받은 뒤 toBlueprint() 로 공통 BlueprintDto 로 바꿔 뒤 단계·관문이 그대로 쓴다
 */
public record BizBlueprintDto(
        String title,
        String summary,
        String code,
        String category,
        List<String> competencies,
        String scenario,
        List<BlueprintDto.Fact> facts,
        List<Flaw> flaws,
        List<BlueprintDto.Judgment> judgments,
        List<String> trapKeywords,
        BlueprintDto.Conclusion conclusion,
        List<BlueprintDto.Table> tables,
        List<BlueprintDto.Figure> figures,
        List<BlueprintDto.Doc> docs,
        List<String> questionPlan,
        List<Case> cases,
        List<String> neededFiles,
        List<BlueprintDto.Filler> fillers) {

    /** 함정: caughtBy 는 문항 번호("Q2") 또는 "결론" */
    public record Flaw(String what, String where, String evidence, String caughtBy) {}

    /** 문항 하나: name 은 Q 번호, ask 는 구할 값, choices 는 객관식 보기(단답형은 빈 목록) */
    public record Case(String name, String situation, String expected, String basis, String ask, List<String> choices) {}

    public BlueprintDto toBlueprint() {
        List<BlueprintDto.Flaw> fs = flaws == null ? List.of() : flaws.stream().filter(f -> f != null).map(f -> new BlueprintDto.Flaw(f.what(), f.where(), f.evidence(), f.caughtBy())).toList();
        List<BlueprintDto.Case> cs = cases == null ? List.of() : cases.stream().filter(c -> c != null)
                .map(c -> new BlueprintDto.Case(c.name(), c.situation(), c.expected(), c.basis(), c.ask() == null ? "" : c.ask(), c.choices() == null ? List.of() : c.choices(), false)).toList();
        return new BlueprintDto(title, summary, code, category, competencies, scenario, List.of(), facts, List.of(), fs, judgments, trapKeywords, conclusion,
                tables, figures, docs, questionPlan, cs, neededFiles, fillers);
    }

    /** 공통 설계도에서 BIZ 칸만 옮긴다(테스트의 가짜 AI 용). 판단 지점 문항 표시(judgment)는 BIZ 설계도에 없다 */
    public static BizBlueprintDto from(BlueprintDto b) {
        List<Flaw> fs = b.flaws() == null ? List.of() : b.flaws().stream().map(f -> new Flaw(f.what(), f.where(), f.evidence(), f.caughtBy())).toList();
        List<Case> cs = b.cases() == null ? List.of() : b.cases().stream().map(c -> new Case(c.name(), c.situation(), c.expected(), c.basis(), c.ask(), c.choices())).toList();
        return new BizBlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.facts(), fs, b.judgments(), b.trapKeywords(),
                b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cs, b.neededFiles(), b.fillers());
    }
}
