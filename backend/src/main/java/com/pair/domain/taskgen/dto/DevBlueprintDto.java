package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * DEV 설계도 단계가 모델에게 받는 모양(2026-11-26 g6: 직군별 설계도). BIZ 칸(숫자표·문서·문항)이 없어 모델이 상관없는 칸을 보지 않는다.
 * 받은 뒤 toBlueprint() 로 공통 BlueprintDto 로 바꿔 뒤 단계·관문이 그대로 쓴다
 */
public record DevBlueprintDto(
        String title,
        String summary,
        String code,
        String category,
        List<String> competencies,
        String scenario,
        List<String> api,
        List<BlueprintDto.Fact> facts,
        List<BlueprintDto.Example> examples,
        List<BlueprintDto.Flaw> flaws,
        List<BlueprintDto.Judgment> judgments,
        List<String> trapKeywords,
        List<Case> cases,
        List<String> neededFiles,
        List<BlueprintDto.Filler> fillers) {

    /** 숨은 테스트 하나: name 은 테스트 메서드 이름 */
    public record Case(String name, String situation, String expected, String basis) {}

    public BlueprintDto toBlueprint() {
        List<BlueprintDto.Case> cs = cases == null ? List.of() : cases.stream().filter(c -> c != null).map(c -> new BlueprintDto.Case(c.name(), c.situation(), c.expected(), c.basis())).toList();
        return new BlueprintDto(title, summary, code, category, competencies, scenario, api, facts, examples, flaws, judgments, trapKeywords, null,
                List.of(), List.of(), List.of(), List.of(), cs, neededFiles, fillers);
    }

    /** 공통 설계도에서 DEV 칸만 옮긴다(테스트의 가짜 AI 용) */
    public static DevBlueprintDto from(BlueprintDto b) {
        List<Case> cs = b.cases() == null ? List.of() : b.cases().stream().map(c -> new Case(c.name(), c.situation(), c.expected(), c.basis())).toList();
        return new DevBlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), cs, b.neededFiles(), b.fillers());
    }
}
