package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloPickDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-26 g8: g7 판정 반영(설명의 결함 지목 검사, 첨부 수 경고, 실제 첨부 글자 수, AI 가 고르는 단독 풀이, 후속 질문 문구) */
class TaskGenG8Test {

    @Test
    @DisplayName("설명 결함 지목 검사(경고): 결함 클래스 이름, 기존 메서드를 대조·검토하라는 문장, 결함 사례 입력을 잡고 '추가:' 요청만 있는 설명은 통과")
    void flawPointer() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint(); // 결함 위치 DiscountLedger.add, 기존 api totalDiscount
        List<String> bad = List.of("기존 totalDiscount() 의 조회 결과를 정산 기록과 대조하고 결제액 조회를 추가하세요.", "합계는 DiscountLedger 가 모읍니다.");
        GenCheckResultDto r = TaskWritingService.flawPointerCheck(b, bad, List.of("새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount() → 3.50"));
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL); // g10: 결함 클래스 이름·기존 메서드 검토 지시는 실패
        assertThat(r.detail()).contains("DiscountLedger").contains("totalDiscount");
        GenCheckResultDto ok = TaskWritingService.flawPointerCheck(b, List.of("결제 화면에 결제액 조회를 추가하세요.", "할인이 원금보다 클 때의 처리 방식을 정하고 선택한 이유를 설명하세요."),
                List.of("새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount() → 3.50"));
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("결함 지목 ③: 결함 사례 따옴표 입력이 설명에 있어도 예(요구사항·설명의 → 줄, 설계도 examples)에 나온 값이면 걸지 않는다(g8 오탐 5개)")
    void flawPointerSkipsExampleValues() {
        BlueprintDto base = TaskPipelineServiceTest.devBlueprint();
        var flawCase = new BlueprintDto.Case("totalsExactly", "addItem(\"나무\", 3), addItem(\"비밀값\", 1) 뒤 합계", "0.30", base.cases().get(0).basis());
        BlueprintDto b = new BlueprintDto(base.title(), base.summary(), base.code(), base.category(), base.competencies(), base.scenario(), base.api(), base.facts(), base.examples(),
                base.flaws(), base.judgments(), base.trapKeywords(), base.conclusion(), base.tables(), base.figures(), base.docs(), base.questionPlan(), List.of(flawCase));
        var ok = TaskWritingService.flawPointerCheck(b, List.of("매장에서는 나무 상자를 주로 판다."), List.of("addItem(\"나무\", 2) 뒤 합계 → 2.00"));
        assertThat(ok.ok()).isTrue();
        var hit = TaskWritingService.flawPointerCheck(b, List.of("지난주 비밀값 항목이 접수되었다."), List.of("addItem(\"나무\", 2) 뒤 합계 → 2.00"));
        assertThat(hit.ok()).isFalse();
        assertThat(hit.detail()).contains("비밀값").doesNotContain("나무");
    }

    @Test
    @DisplayName("첨부 수 경고와 '한도 이하 권장'은 없다(필요한 파일을 턴에 나눠 붙이는 것도 재려는 능력, 사용자 결정)")
    void noAttachCountRule() {
        assertThat(TaskPromptService.devBlueprint(20, TaskPromptService.stageDepth(false, 20, true), true)).doesNotContain("Prefer needed files");
        assertThat(java.util.Arrays.stream(TaskPipelineService.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)).doesNotContain("M6_ATTACH_CHECK");
    }

    @Test
    @DisplayName("BIZ M6 글자 수는 응시 화면이 PDF 를 첨부할 때 보내는 글(쪽 머리 + 쪽 글)과 같은 모양으로 센다")
    void bizAttachedText() {
        assertThat(BizPipelineService.attachedText(List.of("가나다", "라마"))).isEqualTo("--- 1쪽 ---\n가나다\n--- 2쪽 ---\n라마");
    }

    @Test
    @DisplayName("AI 단독 풀이 고르기: 파일 목록(경로·글자 수)만 보이고, 고른 순서대로 한도까지 붙이며 없는·겹친 경로는 버린다")
    void pickAttachesChosenFiles() {
        var files = List.of(new SoloLimitService.Material("src/a/A.java", "a".repeat(100)), new SoloLimitService.Material("NOTES.md", "비밀기록".repeat(10)),
                new SoloLimitService.Material("src/a/B.java", "b".repeat(100)));
        var limits = new AiLimitsDto(10, 2, 1000);
        String q = SoloLimitService.pickPrompt("[설명]\n결제액 조회를 추가하세요.", files, limits);
        assertThat(q).contains("- NOTES.md (40자)").contains("- src/a/A.java (100자)").contains("2개까지").doesNotContain("비밀기록");
        var a = SoloLimitService.attachChosen(files, List.of("NOTES.md", "없는/파일.java", "NOTES.md", "src/a/B.java", "src/a/A.java"), limits);
        assertThat(a.attached()).extracting(SoloLimitService.Material::path).containsExactly("NOTES.md", "src/a/B.java");
        assertThat(a.omitted()).containsExactly("src/a/A.java");
    }

    @Test
    @DisplayName("AI 단독 풀이 고르기: AI 가 고른 파일만 풀이 질문에 들어가고(붙이지 않은 NOTES 내용은 없음), 고르기가 실패하면 이름순으로 붙인다")
    void pickThenSolveUsesOnlyChosen() {
        var ai = new SoloSolveServiceTest.FakeAi();
        ai.script.add(new SoloPickDto(List.of("src/a/A.java")));
        var files = List.of(new SoloLimitService.Material("src/a/A.java", "class A {}"), new SoloLimitService.Material("NOTES.md", "9월 3일 정산 기록"));
        var one = new AiLimitsDto(10, 1, 1000); // 한 번에 1개: 고를 것이 있다
        var p = SoloLimitService.pick(ai, "m", "", 100, "low", "[설명]\n과제", files, one);
        assertThat(p.fallback()).isFalse();
        assertThat(p.attached().attached()).extracting(SoloLimitService.Material::path).containsExactly("src/a/A.java");
        assertThat(SoloLimitService.note(p, one)).contains("AI 가 고른 파일: [src/a/A.java]");
        var failed = SoloLimitService.pick(ai, "m", "", 100, "low", "[설명]\n과제", files, one); // 준비된 답 없음 → 호출 실패
        assertThat(failed.fallback()).isTrue();
        assertThat(failed.attached().attached()).extracting(SoloLimitService.Material::path).containsExactly("src/a/A.java"); // 이름순 앞(폴더가 먼저라 src/ 가 NOTES.md 보다 앞)
        // 다 붙일 수 있으면 모델을 부르지 않고 모두 붙인다
        int calls = ai.users.size();
        var all = SoloLimitService.pick(ai, "m", "", 100, "low", "[설명]\n과제", files, new AiLimitsDto(10, 2, 1000));
        assertThat(ai.users).hasSize(calls);
        assertThat(all.attached().attached()).hasSize(2);
        assertThat(SoloSolveService.REQUEST).contains("접근 범위").contains("바꾸지 말고");
    }

    @Test
    @DisplayName("BIZ 공통 후속 질문에는 DEV 문구(테스트 실행, 로그 대조)가 없다")
    void bizFollowUps() {
        assertThat(String.join(" ", GeneratedTaskRepository.BIZ_FOLLOW_UPS)).doesNotContain("테스트 실행").doesNotContain("로그 대조");
    }

    @Test
    @DisplayName("프롬프트: BIZ 함정은 자료에만 있는 회사 정의(사실 문장 한 번), 일반 상식 함정 금지. DEV 작성은 기존 메서드 검토 지시 금지. BIZ 문서는 독자 요청·다른 자료 이름 금지")
    void prompts() {
        String biz = TaskPromptService.bizBlueprint(20, TaskPromptService.stageDepth(true, 20, true), true);
        assertThat(biz).contains("company definition").contains("Record that definition once").contains("B9 실적 정의").doesNotContain("B6 단위 환산");
        assertThat(TaskPromptService.WRITING_DEV).contains("never ask to review, check or compare existing methods");
        assertThat(TaskPromptService.writingBizDoc(20, true, 2)).contains("never name other materials").doesNotContain("회신해 주세요.\"(request");
        assertThat(TaskPromptService.devBlueprint(20, TaskPromptService.stageDepth(false, 20, true), true)).contains("never the input condition that triggers the flaw");
    }
}
