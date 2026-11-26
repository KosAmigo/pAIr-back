package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-25 g2 실행에서 나온 6가지 문제의 회귀 시험(실제 실패한 문장·구조 사용, AI 는 가짜) */
class GenFixRegressionTest {

    private static BlueprintDto with(BlueprintDto b, List<String> api, List<BlueprintDto.Example> examples, List<BlueprintDto.Flaw> flaws, List<BlueprintDto.Case> cases) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), api, b.facts(), examples, flaws, b.judgments(),
                b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);
    }

    // ── 1. 설계도 형식 ─────────────────────────────

    @Test
    @DisplayName("1: BIZ 는 오답을 가르는 사례(separatedBy)를 요구하지 않는다(11·12). DEV 는 그대로 요구한다")
    void bizDoesNotNeedSeparatedBy() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var f = b.flaws().get(0);
        var withWrong = new BlueprintDto.Flaw(f.what(), f.where(), f.evidence(), f.caughtBy(), "12.5", f.caughtBy()); // 11 의 실제 모양: 오답은 있고 separatedBy 가 caughtBy 와 같음
        BlueprintDto biz = with(b, b.api(), b.examples(), List.of(withWrong), b.cases());
        assertThat(BlueprintService.problems(BlueprintService.normalized(biz, true), true)).noneMatch(p -> p.contains("가르는 사례"));

        BlueprintDto dev = TaskPipelineServiceTest.devBlueprint();
        var d = dev.flaws().get(0);
        var devWrong = new BlueprintDto.Flaw(d.what(), d.where(), d.evidence(), d.caughtBy(), "반올림", d.caughtBy());
        assertThat(BlueprintService.problems(with(dev, dev.api(), dev.examples(), List.of(devWrong), dev.cases()), false)).anyMatch(p -> p.contains("가르는 사례"));
    }

    @Test
    @DisplayName("1: 01 의 api '추가: discount.OrderDiscount.public static long payable()' 에서 메서드 이름 payable 을 읽고, 틀 검사·호출 사례 검사가 통과한다. 사례가 없으면 예시가 든 안내를 준다")
    void addedMethodWithModifiers() {
        String api = "추가: discount.OrderDiscount.public static long payable()";
        assertThat(FeatureCheckService.addedSignature(api)[0]).isEqualTo("payable");
        var code = List.of(new DevDraftDto.FileDraft("src/discount/OrderDiscount.java",
                "package discount;\npublic class OrderDiscount {\n    public static long payable() {\n        throw new UnsupportedOperationException(\"TODO\");\n    }\n}\n"));
        assertThat(FeatureCheckService.stubProblem(api, code)).isNull();

        BlueprintDto dev = TaskPipelineServiceTest.devBlueprint();
        var called = new BlueprintDto.Case("payableNoDiscount", "할인 없이 payable() 호출", "10000", dev.cases().get(0).basis());
        assertThat(BlueprintService.addedMethodCases(with(dev, List.of(api), List.of(), dev.flaws(), List.of(called, dev.cases().get(0))))).isEmpty();
        var none = BlueprintService.addedMethodCases(with(dev, List.of(api), List.of(), dev.flaws(), List.of(dev.cases().get(0))));
        assertThat(none).anyMatch(p -> p.contains("payable() 를 부르는 사례") && p.contains("예:") && p.contains("추가: 클래스.payable("));
        assertThat(TaskPromptService.blueprint(false, 40, "깊이")).contains("수정자나 패키지 이름은 쓰지 않는다").contains("추가: OrderDiscount.payable(): long");
    }

    // ── 2. 사실 한 문장, 비슷한 문장 비교 ─────────────────────────────

    private static final String LONG_FACT = "지난 심사표에는 마루의 판매량 630건·운영 기간 7일·성과지수 90, 보람의 판매량 880건·운영 기간 11일·성과지수 80, 판매 성과 우세 캠페인 마루가 기록되어 있다. "
            + "운영 일지에는 가람의 운영 기간이 30일로 기록되어 있다. 운영 일지에는 나래의 운영 기간이 20일로 기록되어 있다.";

    @Test
    @DisplayName("2: 08 처럼 문장 여럿을 한 사실로 이은 설계도는 거부한다(줄바꿈·'/' 포함)")
    void blueprintRejectsMultiSentenceFacts() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var facts = new java.util.ArrayList<>(b.facts());
        facts.add(new BlueprintDto.Fact(LONG_FACT, b.facts().get(0).place()));
        facts.add(new BlueprintDto.Fact("방문 200건 / 전환 8건", b.facts().get(0).place()));
        var bad = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), facts, b.examples(), b.flaws(), b.judgments(),
                b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases());
        assertThat(BlueprintService.multiSentenceFacts(bad)).hasSize(2);
        assertThat(BlueprintService.multiSentenceFacts(b)).isEmpty();
        assertThat(TaskPromptService.blueprint(true, 40, "깊이")).contains("사실 하나는 문장 하나");
    }

    @Test
    @DisplayName("2: 글에서 문장으로 쪼개지거나 조사·부호가 조금 달라져도 같은 문장으로 보고, 숫자가 바뀌면 다른 문장이다")
    void similarSentencesMatch() {
        String written = "운영 일지에는 나래의 운영 기간은 20일로 기록되어 있다. 지난 심사표에는 마루의 판매량 630건, 운영 기간 7일, 성과지수 90, 보람의 판매량 880건, 운영 기간 11일, 성과지수 80이 적혀 있으며 판매 성과 우세 캠페인 마루가 기록되어 있다.\n"
                + "운영 일지에는 가람의 운영 기간이 30일로 기록되어 있다.";
        assertThat(GenTextService.containsLike(written, LONG_FACT)).isTrue();
        assertThat(GenTextService.containsLike(written.replace("30일", "31일"), LONG_FACT)).isFalse();
        assertThat(GenTextService.containsLike("전혀 다른 글입니다. 운영 일지는 없습니다.", LONG_FACT)).isFalse();

        BlueprintDto dev = TaskPipelineServiceTest.devBlueprint();
        var c = new BlueprintDto.Case("Q1", "상황", "x", "KPI 문서의 검산 예에는 방문 200건 → 전환 8건 → 전환율 4퍼센트가 기록돼 있다.");
        var bp = with(dev, dev.api(), dev.examples(), dev.flaws(), List.of(c));
        assertThat(TaskWritingService.basisCheck(bp, "KPI 문서의 검산 예에는 방문 200건, 전환 8건, 전환율 4퍼센트가 기록되어 있다.").ok()).isTrue();
        assertThat(TaskWritingService.basisCheck(bp, "KPI 문서의 검산 예에는 방문 200건, 전환 9건, 전환율 4퍼센트가 기록되어 있다.").ok()).isFalse();
    }

    // ── 3. 비용 상한: TaskReviewLoopTest.estimatesOnlyRegeneratedStages, 상한 값: TaskGenPropertiesYmlTest ──

    // ── 4. 숨은 테스트가 보이는 예를 덮음 ─────────────────────────────

    @Test
    @DisplayName("4: 05 처럼 추가 메서드 encode() 를 부르는 예(GRID)에 대응하는 사례가 없으면 설계도 문제다")
    void addedMethodExamplesNeedCases() {
        BlueprintDto dev = TaskPipelineServiceTest.devBlueprint();
        String api = "추가: settings.LayoutParser.encode(): String";
        var examples = List.of(new BlueprintDto.Example("new LayoutParser(\"AUTO\").encode()", "\"\""), new BlueprintDto.Example("new LayoutParser(\"GRID\").encode()", "\"layout=GRID\""));
        var auto = new BlueprintDto.Case("encodesAuto", "new LayoutParser(\"AUTO\").encode()", "\"\"", "new LayoutParser(\"AUTO\").encode() → \"\"");
        var problems = BlueprintService.addedMethodCases(with(dev, List.of(api), examples, dev.flaws(), List.of(auto, dev.cases().get(0))));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("GRID").contains("사례(cases)가 없습니다");
        var grid = new BlueprintDto.Case("encodesGrid", "new LayoutParser(\"GRID\").encode()", "\"layout=GRID\"", "new LayoutParser(\"GRID\").encode() → \"layout=GRID\"");
        assertThat(BlueprintService.addedMethodCases(with(dev, List.of(api), examples, dev.flaws(), List.of(auto, grid, dev.cases().get(0))))).isEmpty();
        assertThat(TaskPromptService.blueprint(false, 40, "깊이")).contains("사례가 없는 동작은 숨은 테스트가 보지 않아");
        assertThat(TaskPromptService.answerDev(40)).contains("description·requirements 에 없는 요구");
    }

    // ── 5. 정의가 함정을 없앰 ─────────────────────────────

    @Test
    @DisplayName("5: 10 처럼 계산식이 있어도 정답이 CSV 칸 하나와 같은 값이면 '함정 문항이 자료 대조를 요구함' 실패이고 설계도로 보낸다. 점검 프롬프트에 함정을 풀어 주는 정의가 들어 있다")
    void trapAnswerEqualsCsvCell() {
        BizDraftDto d = BizFixtures.good("c");
        var q3 = d.questions().get(2); // CSV 에 62000 이 있다: 62000 - 5500 + 5500 처럼 꾸며도 값은 칸 그대로
        var disguised = new BizDraftDto.QuestionDraft(q3.type(), q3.content(), List.of(), List.of("62000"),
                "round(v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\") - (5000 + 500) + (5000 + 500), 0)", q3.sources(), q3.commonWrongAnswers());
        var qs = new java.util.ArrayList<>(d.questions());
        qs.set(2, disguised);
        var edited = new BizDraftDto(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(), d.followUps(), d.documents(), d.csvFiles(),
                d.checklist(), d.trap(), qs, d.difficulty(), d.competencies(), d.trapKeywords(), d.judgmentPoints(), d.hiddenFlaws());
        var r = BizCheckService.trapQuestions(edited, "", new BizTableService(d.csvFiles()));
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("Q3").contains(BizCheckService.CELL_SAME);
        assertThat(BizPipelineService.owners(new GenCheckResultDto(BizCheckService.TRAP_QUESTION, false, r.detail()))).containsExactly(Stage.BLUEPRINT);
        // 칸과 다른 값이면 통과하고, 기존의 다른 실패(칸 하나 읽기)는 그대로 정답 쪽
        assertThat(BizCheckService.trapQuestions(d, "", new BizTableService(d.csvFiles())).ok()).isTrue();
        assertThat(BizPipelineService.owners(new GenCheckResultDto(BizCheckService.TRAP_QUESTION, false, "Q2 는 자료 칸 하나를 그대로 읽는 문항입니다"))).containsExactly(Stage.ANSWER);

        String leak = TaskPromptService.check("blueprint-leak");
        assertThat(leak).contains("원본 필드는 운임 및 예약 수수료를 포함한 카드 결제 합계").contains("함정의 정답 해석");
    }

    // ── 6. 군더더기 문장 ─────────────────────────────

    private static final String FILLER = "담당자는 접수된 자료를 검토 중이다. 팀은 관련 부서에 의견을 요청한다. 담당자는 처리 경과가 기록되어 있지 않다고 적었다. 담당자는 확인을 마치지 않았다.";

    @Test
    @DisplayName("6: 숫자 없이 '담당자는/팀은 … 중이다·요청한다·않았다' 로 끝나는 문장이 3개 이상이면 경고하고, 사실 문장(숫자 있음)은 세지 않는다")
    void repeatedFillerWarns() {
        assertThat(TaskWritingService.repeatedFiller(FILLER)).hasSize(3);
        assertThat(TaskWritingService.repeatedFiller("담당자는 접수된 자료를 검토 중이다. 팀은 관련 부서에 의견을 요청한다.")).isEmpty();
        assertThat(TaskWritingService.repeatedFiller("운영팀은 새봄의 전환율을 1퍼센트 증가로 기록했다. 운영팀은 여름의 전환율을 1퍼센트 증가로 기록했다. 담당자는 400건을 확인했다.")).isEmpty();
        var r = TaskWritingService.fillerCheck(FILLER);
        assertThat(r.ok()).isFalse();
        assertThat(r.needsRegeneration()).isFalse(); // 경고만
        assertThat(TaskPromptService.writingBiz(40)).contains("나쁜 예:").contains("좋은 예:").contains("담당자는 자료를 검토 중이다");
    }

    @Test
    @DisplayName("6: 반복 군더더기는 작성 단계에 한 번만 되돌려 보낸다(두 번째 글에도 남아 있어도 더 부르지 않는다)")
    void sendsBackToWritingOnce() {
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.bizBlueprint())
                .add(BizWritingDto.class, TaskPipelineServiceTest.bizWriting(" " + FILLER)).add(BizWritingDto.class, TaskPipelineServiceTest.bizWriting(" " + FILLER))
                .add(BizQuestionsDto.class, TaskPipelineServiceTest.bizQuestions()).add(BizAnswerDto.class, TaskPipelineServiceTest.bizAnswer());
        new BizPipelineService(ai, new TaskGenProperties(null, null, null, null, null)).start(new com.pair.domain.taskgen.dto.TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20)).create(s -> {});
        assertThat(ai.order).containsExactly(BlueprintDto.class, BizWritingDto.class, BizWritingDto.class, BizQuestionsDto.class, BizAnswerDto.class);
        assertThat(ai.users.get(2)).contains("되풀이됩니다").contains("담당자는 접수된 자료를 검토 중이다");
    }

    // ── 7. 난이도 참고 지표(AI 단독 풀이) ─────────────────────────────

    @Test
    @DisplayName("7: 단독 풀이 결과는 항상 경고(warn)라 다시 생성하지 않고, report 에는 놓침·잡음·건너뜀(이유)·확인 못 함(이유)로 남는다")
    void soloOutcomeIsRecordedAndNeverGates() {
        var skipped = SoloSolveService.skipped("다른 검사가 실패해 돌리지 않음");
        var caught = new GenCheckResultDto(SoloSolveService.NAME, true, "참고: AI 단독 풀이(m)가 숨은 결함을 잡았어요(1번 모두 숨은 테스트 통과).", GenCheckResultDto.WARN);
        var missed = new GenCheckResultDto(BizSoloSolveService.NAME, true, "참고: AI 단독 풀이가 숨은 결함을 지나쳤어요. 자료를 그대로 받고 낸 답이 함정 문항에서 틀렸어요: Q1(AI 답 2, 정답 1)", GenCheckResultDto.WARN);
        var error = new GenCheckResultDto(SoloSolveService.NAME, true, "AI 단독 풀이를 확인하지 못했어요(모델 호출 실패: timeout)", GenCheckResultDto.WARN);
        for (var c : List.of(skipped, caught, missed, error)) assertThat(c.needsRegeneration()).isFalse();

        assertThat(TaskGeneratorService.soloOutcome(List.of(skipped))).containsEntry("status", "skipped").containsEntry("reason", "다른 검사가 실패해 돌리지 않음");
        assertThat(TaskGeneratorService.soloOutcome(List.of(caught))).containsEntry("status", "caught").doesNotContainKey("reason");
        assertThat(TaskGeneratorService.soloOutcome(List.of(missed))).containsEntry("status", "missed");
        assertThat(TaskGeneratorService.soloOutcome(List.of(error))).containsEntry("status", "error");
        assertThat(TaskGeneratorService.soloOutcome(List.of(error)).get("reason").toString()).contains("모델 호출 실패: timeout");
        assertThat(TaskGeneratorService.soloOutcome(List.of())).containsEntry("status", "skipped").containsKey("reason"); // 검사가 아예 없어도 이유를 남긴다
    }

    @Test
    @DisplayName("7: 단독 풀이가 결함을 잡아도 과제는 통과하고(경고만), 건너뛰면 report.details.solo 에 이유가 남는다")
    void soloCaughtStillPassesAndSkipReasonIsInReport() {
        for (boolean skip : new boolean[] {false, true}) {
            var check = skip ? SoloSolveService.skipped("pair.taskgen.solo-enabled=false")
                    : new GenCheckResultDto(SoloSolveService.NAME, true, "참고: AI 단독 풀이(m)가 숨은 결함을 잡았어요(1번 모두 숨은 테스트 통과).", GenCheckResultDto.WARN);
            var ai = new TaskPipelineServiceTest.StageAi().add(com.pair.domain.taskgen.dto.ReviewResultDto.class, new com.pair.domain.taskgen.dto.ReviewResultDto(List.of()))
                    .add(com.pair.domain.taskgen.dto.AltSolutionDto.class, new com.pair.domain.taskgen.dto.AltSolutionDto(List.of(new DevDraftDto.FileDraft("src/shop/Cart.java", "package shop; class Cart {}"))));
            var run = new TaskReviewLoopTest.FakeRun(List.of(TaskGenFixtures.edit(TaskGenFixtures.good(), b -> b.description(List.of("금액을 확인하세요.")))));
            var h = new TaskReviewLoopTest.Harness(ai, run, null) {
                @Override
                protected Extra afterChecks(com.pair.domain.taskgen.dto.TaskGenRequest form, DevDraftDto draft, List<GenCheckResultDto> results) {
                    return new Extra(List.of(check), java.util.Map.of(), java.util.Map.of());
                }
            };
            var o = h.generate(TaskGenFixtures.form(), 1, s -> {});
            assertThat(o.passed()).isTrue();
            @SuppressWarnings("unchecked")
            var solo = (java.util.Map<String, Object>) h.report.details().get("solo");
            assertThat(solo).containsEntry("status", skip ? "skipped" : "caught");
            if (skip) assertThat(solo.get("reason").toString()).contains("solo-enabled=false");
        }
    }

    @Test
    @DisplayName("7: 배치 summary 의 단독 풀이 놓침 비율 한 줄은 놓침/돌린 것(놓침+잡음)이고, 안 돌았거나 확인 못 한 과제는 따로 센다")
    void batchSummaryHasSoloMissRate() {
        String[] lines = {"- 01 | DEV | passed=true | solo=missed | fail=", "- 02 | DEV | passed=true | solo=caught | fail=", "- 03 | BIZ | passed=true | solo=caught | fail=",
                "- 04 | BIZ | passed=false | solo=skipped | fail=x", "- 05 | DEV | 예외 | 12초 | boom", null};
        assertThat(TaskGenBatchLiveTest.soloLine(lines)).contains("놓침 1/3 (33%)").contains("확인 못 함 1개");
        assertThat(TaskGenBatchLiveTest.soloLine(new String[] {"- 01 | passed=false | solo=skipped"})).contains("0/0 (-)");
    }

    @Test
    @DisplayName("7: 결함 유형 목록은 단독 풀이가 놓친 유형(D5, B5)을 먼저 두고 나머지는 '쉽게 잡음'으로 표시하며, 설계도 프롬프트에 결함 배치 안내가 있다")
    void flawTypesPutMissedFirstAndPromptGuidesPlacement() {
        String types = TaskPromptService.resource("taskgen/flaw-types.md");
        assertThat(types.indexOf("D5 ")).isLessThan(types.indexOf("D1 "));
        assertThat(types.indexOf("B5 ")).isLessThan(types.indexOf("B1 "));
        assertThat(types).contains("D1 월말").contains("B6 단위 환산").contains("쉽게 잡음");
        assertThat(TaskPromptService.blueprint(true, 40, "깊이")).contains("두 곳 이상을 대조할 때만").contains("단위 열이 함정을 알려 주는 CSV");
    }
}
