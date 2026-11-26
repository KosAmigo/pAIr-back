package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-26 g9: g8 판정 반영(결함 사례는 AI 기본 풀이로 틀림, BIZ 요청 글·문항의 필요한 자료 지목 검사, 추천 함정 유형 = 결함 유형 목록) */
class TaskGenG9Test {

    @Test
    @DisplayName("BIZ 자료 지목(경고): 필요한 자료 이름(띄어쓰기 무시)이나 이름의 낱말이 한 문장에 모두 있으면 걸고, 요청 글/문항을 나눠 적는다")
    void bizMaterialPointer() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint().withMaterials(List.of("자료/시설별 전력 사용량.csv", "자료/계량기 안내서.pdf"), List.of());
        GenCheckResultDto hit = TaskWritingService.bizMaterialPointerCheck(b, List.of("시설별 전력 사용량 자료와 계량기 안내서를 대조하고 기획안을 쓰세요."), List.of(),
                List.of("계량기안내서를 참고해 6월 사용량을 구하면 몇 kWh 입니까?")); // g9: 짧은 이름은 지목 동사(참고)와 함께일 때만
        assertThat(hit.ok()).isFalse();
        assertThat(hit.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(hit.detail()).contains("[요청 글]").contains("[문항]").contains("시설별 전력 사용량");
        assertThat(BizPipelineService.owners(hit)).contains(TaskPipelineService.Stage.WRITING, TaskPipelineService.Stage.QUESTIONS);
        GenCheckResultDto ok = TaskWritingService.bizMaterialPointerCheck(b, List.of("시설 전력 비용을 검토해 1페이지 기획안을 쓰세요."), List.of("결론과 근거 3개"),
                List.of("6월 전체 사용량은 몇 kWh 입니까?"));
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("g9 판정: 짧은 자료 이름('문의 이력')이 배경 문장에 있으면 걸지 않고, 지목 동사(대조·확인·참고)와 함께 있으면 건다")
    void shortNamesNeedPointingVerb() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint().withMaterials(List.of("자료/문의 이력.csv", "자료/생산 마감.pdf"), List.of());
        assertThat(TaskWritingService.bizMaterialPointerCheck(b, List.of("고객지원팀은 문의 이력과 업무 문서를 바탕으로 응답 현황을 정리하고 있다.", "생산관리팀은 월말 생산 마감을 마쳤다."),
                List.of(), List.of()).ok()).isTrue();
        var hit = TaskWritingService.bizMaterialPointerCheck(b, List.of("문의 이력을 운영 기록과 대조해 응답 현황을 점검하세요."), List.of(), List.of());
        assertThat(hit.ok()).isFalse();
        assertThat(hit.detail()).contains("문의 이력");
    }

    @Test
    @DisplayName("비용 상한은 직군·제한시간별(dev 0.6, biz-20 0.6, biz-40 0.8, biz-60 1.0)이고, 키가 없으면 cost-cap")
    void costCapsByTrackAndMinutes() {
        var caps = java.util.Map.of("dev", 0.6, "biz-20", 0.6, "biz-40", 0.8, "biz-60", 1.0);
        var p = new com.pair.global.config.TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0.5, null, null, caps);
        assertThat(p.costCap(true, 60)).isEqualTo(0.6);
        assertThat(p.costCap(false, 20)).isEqualTo(0.6);
        assertThat(p.costCap(false, 40)).isEqualTo(0.8);
        assertThat(p.costCap(false, 60)).isEqualTo(1.0);
        var old = new com.pair.global.config.TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0.5, null, null);
        assertThat(old.costCap(false, 60)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("g10 결함 지목 ④: 기존 동작을 기록과 대조·검토하라는 설명 문장은 실패(작성 다시). '~하는 기능'으로 새 기능을 말하는 문장은 걸지 않는다. ③ 만 있으면 경고")
    void existingBehaviourComparisonFails() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        List<String> req = List.of("새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount() → 3.50");
        for (String bad : List.of("이번 개발 요청서에는 기존 마감일 계산을 확정 대장과 대조하고 날짜별 집계 조회를 추가하는 작업이 적혀 있다.",
                "이번 작업 접수표에는 기존 금액 조회 결과 점검, 선택 항목 합계 조회 추가가 차례로 적혀 있다.",
                "인사 운영팀은 월별 복지 포인트 지급 내역을 대조하는 작업과 잔여 지급액 조회 기능을 등록했다.")) {
            var r = TaskWritingService.flawPointerCheck(b, List.of(bad), req);
            assertThat(r.ok()).as(bad).isFalse();
            assertThat(r.level()).as(bad).isNotEqualTo(GenCheckResultDto.WARN);
        }
        var ok = TaskWritingService.flawPointerCheck(b, List.of("경비 담당자는 출장별 일비 지급액을 조회하는 기존 기능과 함께 식사 제공분 공제액 합계를 확인하는 기능을 요청했다."), req);
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("g10 설계도: 결함 사례가 요구사항 예에 없는 메서드만 부르면 걸린다(g9 01). DEV 좋은 예는 통과")
    void flawCaseThroughRequirementMethod() throws Exception {
        BlueprintDto ex = BlueprintService.normalized(new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(TaskPromptService.DEV_EXAMPLE, com.pair.domain.taskgen.dto.DevBlueprintDto.class).toBlueprint(), false);
        assertThat(DevBlueprintService.flawCasesInRequirements(ex)).isEmpty();
        var cases = new java.util.ArrayList<>(ex.cases());
        cases.set(0, new BlueprintDto.Case(cases.get(0).name(), "eligible(30000) 판정", "true", cases.get(0).basis()));
        BlueprintDto bad = new BlueprintDto(ex.title(), ex.summary(), ex.code(), ex.category(), ex.competencies(), ex.scenario(), ex.api(), ex.facts(), ex.examples(), ex.flaws(),
                ex.judgments(), ex.trapKeywords(), ex.conclusion(), ex.tables(), ex.figures(), ex.docs(), ex.questionPlan(), cases, ex.neededFiles(), ex.fillers());
        assertThat(DevBlueprintService.flawCasesInRequirements(bad)).singleElement().satisfies(p -> assertThat(p).contains("eligible"));
        assertThat(TaskPromptService.resource("taskgen/flaw-types-dev.md")).contains("opposite of what an AI writes by default");
        // g10: 설명 자리 사실이 기존 동작 대조 작업을 말하면 설계도에서 걸린다(작성 단계는 사실을 그대로 넣어야 해서 못 고침)
        var facts = new java.util.ArrayList<>(ex.facts());
        facts.add(new BlueprintDto.Fact("이번 개발 요청서에는 기존 날짜별 조회 결과를 정산 내역과 대조하고 기간 합계 조회를 추가하는 작업이 등록되었다.", BlueprintService.DESCRIPTION));
        BlueprintDto cmp = new BlueprintDto(ex.title(), ex.summary(), ex.code(), ex.category(), ex.competencies(), ex.scenario(), ex.api(), facts, ex.examples(), ex.flaws(),
                ex.judgments(), ex.trapKeywords(), ex.conclusion(), ex.tables(), ex.figures(), ex.docs(), ex.questionPlan(), ex.cases(), ex.neededFiles(), ex.fillers());
        assertThat(BlueprintService.problems(cmp, false)).anyMatch(p -> p.contains("대조·검토하는 작업"));
        assertThat(BlueprintService.problems(ex, false)).isEmpty();
    }

    @Test
    @DisplayName("주제·함정 추천의 함정 유형은 설계도 결함 유형 목록과 같은 이름이고 일반 버그(정수 넘침·null·캐시)·상식 함정(단위 혼동·세금 포함)은 없다")
    void suggestionTypesMatchFlawTypes() {
        String dev = TaskPromptService.resource("taskgen/flaw-types-dev.md"), biz = TaskPromptService.resource("taskgen/flaw-types-biz.md");
        for (String t : TaskSuggestionService.DEV_TYPES.split(", ")) assertThat(dev).contains(t);
        assertThat(TaskSuggestionService.DEV_TYPES).doesNotContain("오버플로").doesNotContain("null").doesNotContain("캐시");
        assertThat(TaskSuggestionService.BIZ_TYPES).doesNotContain("단위 혼동").doesNotContain("세금");
        assertThat(biz).contains("실적 정의").contains("집계 기준").contains("단수 처리");
    }

    @Test
    @DisplayName("프롬프트: 결함 사례 하나 이상은 AI 기본 풀이(가장 단순한 관용 구현)로 틀리는 입력이고, 오답 하나는 AI 기본 풀이다. BIZ 요청 글·문항은 자료 이름을 대지 않는다")
    void prompts() {
        String dev = TaskPromptService.devBlueprint(40, TaskPromptService.stageDepth(false, 40, true), true);
        assertThat(dev).contains("AI default solution =").contains("start.plusMonths(i)").contains("wrongAnswer is the AI default solution itself");
        assertThat(TaskPromptService.answerDev(40)).contains("One wrong fix is always the AI's default solution");
        assertThat(TaskPromptService.WRITING_BIZ_BRIEF).contains("never name a material by its title");
        assertThat(TaskPromptService.QUESTIONS_BIZ).contains("never name a material by its title");
    }
}
