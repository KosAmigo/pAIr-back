package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.global.config.TaskGenProperties;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 이미 있는 기능을 추가하라는 글, 사례 근거 문장, 사례별 테스트 근거 입력, report.json 한글 보존을 AI 없이 확인한다 */
class FeatureCheckServiceTest {

    private static final String IMPLEMENTED = "package billing;\npublic class Schedule {\n    public int countDue(int n) {\n        return n + 1;\n    }\n}\n";
    private static final String STUB = "package billing;\npublic class Schedule {\n    public int countDue(int n) {\n        throw new UnsupportedOperationException(\"todo\");\n    }\n}\n";

    private static List<DevDraftDto.FileDraft> code(String content) {
        return List.of(new DevDraftDto.FileDraft("src/billing/Schedule.java", content), new DevDraftDto.FileDraft("test/billing/ScheduleTest.java", "class ScheduleTest {}"));
    }

    private static BlueprintDto bp(String... api) {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), List.of(api), b.facts(), b.examples(), b.flaws(), b.judgments(),
                b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases());
    }

    @Test
    @DisplayName("추가·구현하라는 메서드가 시작 코드에 이미 구현돼 있으면 실패, 틀(예외만 던짐·빈 본문)이거나 '기존' 메서드를 점검하라는 말이면 통과")
    void existingFeatureCheck() {
        String ask = "countDue(int)를 추가하세요. 기존 billingDates(int)는 점검하세요.";
        assertThat(FeatureCheckService.asked(ask)).containsExactly("countDue");

        var failed = FeatureCheckService.check(bp("기존: Schedule.billingDates(int)"), ask, code(IMPLEMENTED));
        assertThat(failed.ok()).isFalse();
        assertThat(failed.needsRegeneration()).isTrue();
        assertThat(failed.detail()).contains("countDue").contains("src/billing/Schedule.java").startsWith(FeatureCheckService.WRITING_STAGE);

        assertThat(FeatureCheckService.check(bp(), ask, code(STUB)).ok()).isTrue();
        assertThat(FeatureCheckService.check(bp(), ask, code(IMPLEMENTED.replace("return n + 1;", ""))).ok()).isTrue(); // 빈 본문
        assertThat(FeatureCheckService.check(bp(), "기존 countDue(int)를 점검하고 고치세요.", code(IMPLEMENTED)).ok()).isTrue();
        assertThat(FeatureCheckService.check(bp(), "합계를 구하세요.", code(IMPLEMENTED)).ok()).isTrue();
    }

    @Test
    @DisplayName("라우팅: 설계도에서 '추가:' 로 적은 메서드가 구현돼 있으면 시작 코드 단계, 그 밖이면 작성 단계로 다시 만든다")
    void featureFailureRouting() {
        String ask = "countDue(int)를 추가하세요.";
        var writingFault = FeatureCheckService.check(bp("기존: Schedule.countDue(int)"), ask, code(IMPLEMENTED));
        var codeFault = FeatureCheckService.check(bp("추가: Schedule.countDue(int)"), ask, code(IMPLEMENTED));

        assertThat(DevPipelineService.owners(writingFault, List.of())).containsExactly(Stage.WRITING);
        assertThat(DevPipelineService.owners(codeFault, List.of())).containsExactly(Stage.CODE);
        assertThat(codeFault.detail()).startsWith(FeatureCheckService.CODE_STAGE);
        assertThat(TaskWritingService.writerFeedback(List.of(writingFault), "countDue(int)를 추가하세요.")).contains("이미 있는 메서드는 추가·구현하라고 쓰지 않는다");
    }

    @Test
    @DisplayName("이미 있는 메서드를 소개하는 문장(기존, 에 더해, 이미 있는, 공개 API인)은 추가하라는 말이 아니다. 새 메서드에 붙은 추가하라는 말은 그대로 잡는다")
    void existingMethodIntroIsNotAnAsk() {
        String intro = "기존 공개 API인 UsagePeriod.usageDays(ZonedDateTime start, ZonedDateTime end)에 더해 overdueDays(ZonedDateTime due, ZonedDateTime back)를 추가하세요.";
        assertThat(FeatureCheckService.asked(intro)).containsExactly("overdueDays");
        assertThat(FeatureCheckService.asked("UsagePeriod.usageDays(ZonedDateTime a, ZonedDateTime b)에 더해 통계를 추가하세요.")).isEmpty();
        assertThat(FeatureCheckService.asked("이미 있는 usageDays(ZonedDateTime a, ZonedDateTime b)를 확장해 구현하세요.")).isEmpty();
        assertThat(FeatureCheckService.asked("usageDays(ZonedDateTime a, ZonedDateTime b)를 추가하세요.")).containsExactly("usageDays");
        // 앞 호출이 기존이어도 뒤 호출에 붙은 추가는 그대로
        assertThat(FeatureCheckService.asked("기존 A.find(int a)를 쓰는 B.count(int b)를 추가하세요.")).containsExactly("count");
    }

    @Test
    @DisplayName("사례 근거 문장이 작성 후 글에 없으면 실패하고 작성 단계로 간다(띄어쓰기·문장부호 차이는 무시)")
    void basisQuoteCheck() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        String basis = b.cases().get(0).basis();

        assertThat(TaskWritingService.basisCheck(b, "앞 글. " + basis + " 뒤 글.").ok()).isTrue();
        assertThat(TaskWritingService.basisCheck(b, basis.replace(" ", "  ").replace(".", " .")).ok()).isTrue(); // 띄어쓰기·문장부호 차이
        var missing = TaskWritingService.basisCheck(b, "근거 문장이 빠진 글입니다.");
        assertThat(missing.ok()).isFalse();
        assertThat(missing.detail()).contains("totalsExactly");
        assertThat(DevPipelineService.owners(missing, List.of())).containsExactly(Stage.WRITING);
        assertThat(BizPipelineService.owners(missing)).containsExactly(Stage.WRITING);
    }

    @Test
    @DisplayName("테스트 근거 점검 입력은 사례마다 기대 결과·근거 문장·그 사례의 테스트 본문을 묶어 준다. 이름이 없는 사례는 '없음'으로 표시")
    void backingInputIsPerCase() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        DevDraftDto d = TaskGenFixtures.good();
        var tests = List.of(new DevDraftDto.FileDraft("test/shop/H.java", "class H {\n    @Test\n    void totalsExactly() {\n        assertEquals(\"0.30\", total());\n    }\n    @Test\n    void other() {\n        assertEquals(1, 1);\n    }\n}"));
        DevDraftDto withTests = TaskGenFixtures.edit(d, x -> x.hiddenTests(tests));

        assertThat(GenTextService.testBodies(tests)).containsOnlyKeys("totalsExactly", "other");
        assertThat(GenTextService.testBodies(tests).get("totalsExactly")).contains("assertEquals(\"0.30\", total())").doesNotContain("assertEquals(1, 1)");

        String in = TaskReviewService.devInput(TaskReviewService.Check.BACKING, TaskGenFixtures.form(), b, withTests);
        assertThat(in).contains("사례 totalsExactly | 기대 결과: 0.30 | 근거 문장: " + b.cases().get(0).basis()).contains("assertEquals(\"0.30\", total())");
        String missing = TaskReviewService.devInput(TaskReviewService.Check.BACKING, TaskGenFixtures.form(), b, d);
        assertThat(missing).contains("(이 이름의 테스트 없음)");
        assertThat(TaskPromptService.check("backing-cases")).contains("기대 결과를 직접 정하는가").contains("사례를 바꾸거나 뺀다");
        // 설계도 사례가 없으면 사례별 점검은 하지 않는다: 숨은 테스트 전체만 주고 단정별 프롬프트로 본다
        String noCases = TaskReviewService.devInput(TaskReviewService.Check.BACKING, TaskGenFixtures.form(), null, withTests);
        assertThat(in).contains(TaskReviewService.CASES_MARK);
        assertThat(noCases).doesNotContain(TaskReviewService.CASES_MARK).contains("[숨은 테스트 test/shop/H.java]");
        assertThat(TaskPromptService.check("backing")).doesNotContain("기대 결과를 직접 정하는가").contains("null").contains("정렬").contains("예외").contains("경계값");
        // 프롬프트도 입력에 맞춰 고른다
        var ai = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, new ReviewResultDto(List.of()));
        var reviewer = new TaskReviewService(new StageCallService(ai, new TaskGenProperties(null, null, null, null, null), true));
        reviewer.run(TaskReviewService.Check.BACKING, TaskPipelineService.Stage.REVIEW, in);
        reviewer.run(TaskReviewService.Check.BACKING, TaskPipelineService.Stage.REVIEW, noCases);
        assertThat(ai.systems.get(0)).contains("기대 결과를 직접 정하는가");
        assertThat(ai.systems.get(1)).doesNotContain("기대 결과를 직접 정하는가");
    }

    @Test
    @DisplayName("프롬프트: 설계도 api 는 기존/추가를 구분하고, 작성의 후속 질문은 일반 틀로만, 답 노출 점검은 '어디를 확인하라'는 위치 힌트를 위반으로 본다")
    void promptRules() {
        assertThat(TaskPromptService.blueprint(false, 20, "깊이")).contains("\"기존: \"").contains("추가: PaymentDiscount.payable").contains("기대 결과를 직접 정하는 예 문장");
        assertThat(TaskPromptService.WRITING_DEV).contains("\"기존:\" 메서드는 이미 있는 것이라").contains("일반 질문 틀").contains("어디를 확인하라는 말도 쓰지 않는다");
        assertThat(TaskPromptService.writingBiz(20)).contains("일반 질문 틀").contains("어디를 확인하라는 말도 쓰지 않는다");
        assertThat(TaskPromptService.code("깊이")).contains("\"추가:\" 메서드는 시작 코드에 반드시 틀로 둔다");
        assertThat(TaskPromptService.check("leak")).contains("결함이 있는 곳을 콕 집어 확인하라고 한다").contains("후속 질문과 문항 문구 포함");
        // 후속 질문은 답 노출 점검 입력에 들어 있다(DEV·BIZ)
        DevDraftDto dev = TaskGenFixtures.edit(TaskGenFixtures.good(), x -> x.taskFollowUp("후속 질문입니다 비고란을 함께 확인하세요"));
        assertThat(TaskReviewService.devInput(TaskReviewService.Check.LEAK, TaskGenFixtures.form(), TaskPipelineServiceTest.devBlueprint(), dev)).contains("[후속 질문]").contains("비고란을 함께 확인하세요");
        var biz = BizFixtures.good("x");
        assertThat(TaskReviewService.bizInput(TaskReviewService.Check.LEAK, TaskGenFixtures.form(), TaskPipelineServiceTest.bizBlueprint(), biz)).contains("[후속 질문]").contains(biz.followUps().get(2));
    }

    @Test
    @DisplayName("report.json: 한글이 attemptLog·gates·checks 에 그대로 저장되고(UTF-8) 파일로 쓰고 다시 읽어도 같다")
    void koreanSurvivesSaveAndReload(@TempDir Path tmp) throws Exception {
        var problems = List.of(new TaskGeneratorService.Problem("답 노출 점검(1차)", "fail", "M5 [description] 원인을 찾아 → 지운다"));
        var report = new TaskGeneratorService.GenReport("gpt-6.1-terra, claude-sonnet-5-5", 2, 10, 1, 2, false, TaskGenFixtures.form(),
                List.of(new GenCheckResultDto("형식", true, "경로와 필수 항목이 모두 맞습니다.")), "다시 생성 실패: 한글 오류",
                Map.of("gates", Map.of("rounds", List.of(Map.of("gate", "테스트 근거 점검", "detail", "근거 문장이 없습니다")))),
                List.of(new TaskGeneratorService.AttemptLog(1, false, 3, problems)));
        ObjectMapper json = new ObjectMapper();
        String text = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Path file = tmp.resolve("report.json");
        Files.writeString(file, text, StandardCharsets.UTF_8);

        byte[] bytes = Files.readAllBytes(file);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("�").contains("답 노출 점검(1차)").contains("테스트 근거 점검").contains("한글 오류").contains("형식");
        var tree = json.readTree(bytes);
        assertThat(tree.at("/attemptLog/0/problems/0/name").asText()).isEqualTo("답 노출 점검(1차)");
        assertThat(tree.at("/details/gates/rounds/0/detail").asText()).isEqualTo("근거 문장이 없습니다");
        assertThat(tree.at("/checks/0/name").asText()).isEqualTo("형식");
    }
}
