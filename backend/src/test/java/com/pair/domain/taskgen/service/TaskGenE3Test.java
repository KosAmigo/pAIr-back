package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevBlueprintDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-26 g7: g6-dev 판정 반영(새 E3, 결함 파일 숨김, 재현 기록 금지, 생성기 정보 노출, 묶음에서 공개 테스트 뺌) */
class TaskGenE3Test {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static BlueprintDto devExample() throws Exception {
        return BlueprintService.normalized(JSON.readValue(TaskPromptService.DEV_EXAMPLE, DevBlueprintDto.class).toBlueprint(), false);
    }

    @Test
    @DisplayName("DEV 좋은 예: 결함 클래스(RewardPolicy)는 api·예·설명 사실에 없고, 결함 근거는 틀린 출력이 아닌 다른 입력의 정산 기록이다")
    void devExampleHidesFlawClass() throws Exception {
        BlueprintDto b = devExample();
        assertThat(DevBlueprintService.flawClasses(b)).containsExactly("RewardPolicy");
        assertThat(DevBlueprintService.flawHidden(b)).isEmpty();
        assertThat(b.flaws().get(0).what()).startsWith("R3 ");
        assertThat(TaskPromptService.DEV_EXAMPLE).doesNotContain("0.30000000000000004").doesNotContain("PaymentDiscount");
    }

    @Test
    @DisplayName("결함 클래스가 공개 API·예·설명 자리 사실에 있으면 설계도 형식 검사에서 걸린다")
    void flawClassInApiIsRejected() throws Exception {
        BlueprintDto b = devExample();
        List<String> api = new ArrayList<>(b.api());
        api.add("기존: RewardPolicy.pointsFor(List<Long> amounts): long");
        List<BlueprintDto.Fact> facts = new ArrayList<>(b.facts());
        facts.add(new BlueprintDto.Fact("주문 적립금은 RewardPolicy 가 계산한다.", BlueprintService.DESCRIPTION));
        BlueprintDto bad = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), api, facts, b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases(), b.neededFiles(), b.fillers());
        assertThat(DevBlueprintService.flawHidden(bad)).hasSize(2).allMatch(p -> p.contains("RewardPolicy"));
        assertThat(BlueprintService.problems(bad, false)).anyMatch(p -> p.contains("공개 API(api)에 있습니다"));
    }

    @Test
    @DisplayName("프롬프트: 새 E3(업무 규칙 대 AI 기본값), 결함 파일은 api 밖, 틀린 출력 재현 기록 금지, 생성기 정보 금지가 설계도·작성 프롬프트에 있다")
    void promptsCarryNewRules() {
        String dev = TaskPromptService.devBlueprint(20, TaskPromptService.stageDepth(false, 20, true), true);
        assertThat(dev).contains("business rule recorded only in the materials").contains("Flaw file:").contains("Never write the flaw's wrong output")
                .contains("12. The flawed class").contains("Public tests do not count").contains("Never put generator information");
        String biz = TaskPromptService.bizBlueprint(20, TaskPromptService.stageDepth(true, 20, true), true);
        assertThat(biz).contains("Trap (E3)").contains("B7 경계 포함 여부");
        assertThat(TaskPromptService.WRITING_DEV).contains("Never add a record of a wrong output").contains("Never write generator information");
        assertThat(TaskPromptService.WRITING_BIZ_BRIEF).contains("Never write generator information");
        assertThat(TaskPromptService.writingBizDoc(20, true, 2)).contains("Never write generator information");
        assertThat(TaskPromptService.code("x", true, 20)).contains("public tests not counted");
    }

    @Test
    @DisplayName("생성기 정보 노출 검사(경고): 파일 수·글자 수·맥락 한도 표현을 잡고, 보통 업무 글은 통과한다")
    void genMetaCheck() {
        var leak = TaskWritingService.genMetaCheck("주문 기록을 정리했다.\n필요한 파일 3개, 합계 2,400자 안팎으로 맞췄다.\n맥락 한도 안에서 첨부하면 된다.");
        assertThat(leak.ok()).isFalse();
        assertThat(leak.level()).isEqualTo(com.pair.domain.taskgen.dto.GenCheckResultDto.WARN);
        assertThat(leak.detail()).contains("2,400자").contains("맥락 한도");
        var ok = TaskWritingService.genMetaCheck("8월 2일 정산 기록: 12,345원 상품 1개 주문에 적립금 120원이 지급되었다.\n고객센터는 3명이 교대로 응대했다.");
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("M6 필요한 묶음 크기는 공개 테스트(test/…)를 빼고 센다")
    void bundleExcludesPublicTests() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint().withMaterials(List.of("src/shop/A.java", "test/shop/ATest.java"), List.of());
        var limit = new AiLimitsDto(10, 2, 1000);
        Map<String, String> mats = Map.of("src/shop/A.java", "x".repeat(500), "test/shop/ATest.java", "x".repeat(400));
        assertThat(MaterialGateService.bundleChars(mats, b.neededFiles())).isEqualTo(500);
        assertThat(MaterialGateService.needed(b, mats, limit).ok()).isTrue(); // 900 이면 70% 넘지만 공개 테스트를 빼면 500
        assertThat(MaterialGateService.neededMin(b, mats, limit).ok()).isTrue();
    }
}
