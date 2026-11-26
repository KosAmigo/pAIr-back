package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 글 검사(같은 문장 되풀이, 한자), 오답을 가르는 사례, 추가 메서드 호출 검사 */
class TaskTextCheckTest {

    @Test
    @DisplayName("같은 문장이 문서에 2번 이상 나오면 실패(띄어쓰기·문장부호 무시). 쪽 제목(첫 줄)이 같은 것은 괜찮고, 설명·요구사항과 문서의 같은 문장은 설계도 사실일 때만 센다")
    void duplicateSentences() {
        BlueprintDto bp = TaskPipelineServiceTest.bizBlueprint();
        String fact = bp.facts().get(0).text();
        String page1 = "2024년 상반기 운영 메모\n수납 기록과 공제 기록을 대조했다.";
        String page2 = "2024년 상반기 운영 메모\n제작 담당자는 같은 소재를 반복 사용했다고 보고했다.";
        assertThat(TaskWritingService.duplicateCheck(List.of(page1, page2), "근거를 자료 수치로 제시하세요 라고 안내합니다.\n근거를 자료 수치로 제시하세요 라고 안내합니다.", bp).ok()).isTrue();

        var twice = TaskWritingService.duplicateCheck(List.of(page1, page2 + "\n수납 기록과  공제 기록을 대조했다 ."), "", bp);
        assertThat(twice.ok()).isFalse();
        assertThat(twice.detail()).contains("2번").contains("공제 기록을 대조했다");
        assertThat(BizPipelineService.owners(twice)).containsExactly(TaskPipelineService.Stage.WRITING);
        assertThat(TaskWritingService.writerFeedback(List.of(twice), page2 + "\n수납 기록과  공제 기록을 대조했다 .")).contains("고칠 문장").contains("같은 문장은 한 번만");
        // 사실 문장이 설명에도 문서에도 있으면 센다
        assertThat(TaskWritingService.duplicateCheck(List.of("제목\n" + fact), fact, bp).ok()).isFalse();
    }

    @Test
    @DisplayName("한국어 글에 한자(중국어 글자)가 있으면 실패하고, 어느 단계의 글인지 표시가 남는다. 한글·영문·숫자만이면 통과")
    void ideographs() {
        Map<String, String> clean = new LinkedHashMap<>();
        clean.put("[작성]", "결론은 새봄 캠페인이다. ROI 12%");
        clean.put("[정답]", "## 필수 항목\n1. 결론");
        assertThat(TaskWritingService.hanCheck(clean).ok()).isTrue();

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("[작성]", "깨끗한 글");
        bad.put("[정답]", "## 필수 항목\n1. 结论: 새봄만 배정한다");
        var r = TaskWritingService.hanCheck(bad);
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("[정답]").contains("结论").doesNotContain("[작성]");
        assertThat(BizPipelineService.owners(r)).containsExactly(TaskPipelineService.Stage.ANSWER);
        assertThat(TaskWritingService.hanCheck(Map.of("[작성]", "結論은 이렇다")).ok()).isFalse(); // 번체·일본식 한자도 같은 블록
    }

    private static BlueprintDto withFlaw(BlueprintDto b, BlueprintDto.Flaw f, List<BlueprintDto.Case> cases) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(),
                List.of(f), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);
    }

    @Test
    @DisplayName("설계도가 오답 방식을 적으면 그 오답을 정답과 가르는 다른 사례(separatedBy)가 있어야 하고, 정답 쪽 프롬프트는 그 사례로 오답을 떨어뜨리게 한다")
    void wrongAnswerNeedsSeparatingCase() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var flaw = b.flaws().get(0);
        var sep = new BlueprintDto.Case("roundingDiffers", "할인 0.125, 0.125 등록 뒤 합계", "0.250", "할인 0.125, 0.125를 등록한 뒤 totalDiscount() → 0.250");
        var cases = List.of(b.cases().get(0), sep);

        var none = new BlueprintDto.Flaw(flaw.what(), flaw.where(), flaw.evidence(), flaw.caughtBy(), "반올림으로 때운다", null);
        assertThat(BlueprintService.problems(withFlaw(b, none, cases), false)).anyMatch(p -> p.contains("가르는 사례"));
        var same = new BlueprintDto.Flaw(flaw.what(), flaw.where(), flaw.evidence(), flaw.caughtBy(), "반올림으로 때운다", flaw.caughtBy());
        assertThat(BlueprintService.problems(withFlaw(b, same, cases), false)).anyMatch(p -> p.contains("가르는 사례"));
        var ok = new BlueprintDto.Flaw(flaw.what(), flaw.where(), flaw.evidence(), flaw.caughtBy(), "반올림으로 때운다", "roundingDiffers");
        assertThat(BlueprintService.problems(withFlaw(b, ok, cases), false)).noneMatch(p -> p.contains("가르는 사례"));

        assertThat(TaskPromptService.blueprint(false, 40, "깊이")).contains("flaws.wrongAnswer").contains("separatedBy");
        assertThat(TaskPromptService.answerDev(40)).contains("flaws.wrongAnswer").contains("separatedBy").contains("직접 호출");
        // 정답 쪽 입력의 설계도에 오답 방식이 그대로 실린다
        assertThat(StageCallService.json(BlueprintService.withoutFacts(withFlaw(b, ok, cases)))).contains("반올림으로 때운다").contains("roundingDiffers");
    }

    @Test
    @DisplayName("추가하라는 메서드를 숨은 테스트가 한 번도 호출하지 않으면 실패하고 정답 쪽 단계로 간다. 기존 메서드는 보지 않는다")
    void addedMethodMustBeCalled() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var bp = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(),
                List.of("기존: RentalPeriod.elapsedDays(ZonedDateTime start, ZonedDateTime end): long", "추가: RentalPeriod.overdueDays(ZonedDateTime dueAt, ZonedDateTime returnedAt): long"),
                b.facts(), b.examples(), b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases());
        var without = List.of(new DevDraftDto.FileDraft("test/rental/HiddenTest.java", "class HiddenTest { @Test void a() { assertEquals(2, RentalPeriod.elapsedDays(s, e)); } }"));
        var with = List.of(new DevDraftDto.FileDraft("test/rental/HiddenTest.java", "class HiddenTest { @Test void a() { assertEquals(0, RentalPeriod.overdueDays(d, r)); } }"));

        var bad = CaseCheckService.addedCalled(bp, without);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).contains("overdueDays()").doesNotContain("elapsedDays");
        assertThat(DevPipelineService.owners(bad, List.of())).containsExactly(TaskPipelineService.Stage.ANSWER);
        assertThat(CaseCheckService.addedCalled(bp, with).ok()).isTrue();
    }

    @Test
    @DisplayName("BIZ 문서에 과제 설계자의 말(응시자, AI 어시스턴트, 기획안에서, 결론을 덧붙이지)이 있으면 실패하고 작성 단계로 간다. 실제 업무 문장은 통과")
    void metaSentencesInDocuments() {
        String real = "집행 확인서\n제작 담당자는 같은 광고 소재를 반복 사용했다고 보고했다.\n정산 담당자는 수납 기록과 공제 기록을 대조했다.";
        assertThat(TaskWritingService.metaCheck(real).ok()).isTrue();

        String meta = real + "\n이 페이지는 집행 내역을 정리하며 결론을 덧붙이지 않는다.\n이 내용은 응시자가 작성하는 기획안에서 이어진다.\nAI 어시스턴트와 자료를 읽는 담당자는 합계를 확인한다.";
        var r = TaskWritingService.metaCheck(meta);
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("이 페이지는").contains("응시자가 작성하는 기획안에서").contains("AI 어시스턴트");
        assertThat(BizPipelineService.owners(r)).containsExactly(TaskPipelineService.Stage.WRITING);
        assertThat(TaskWritingService.writerFeedback(List.of(r), meta)).contains("실제 업무 문서").contains("고칠 문장");
        assertThat(TaskPromptService.writingBiz(40)).contains("실제 회사의 업무 문서").contains("응시자");
    }

    private static BlueprintDto withApi(List<String> api) {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), api, b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases());
    }

    private static List<DevDraftDto.FileDraft> src(String body) {
        return List.of(new DevDraftDto.FileDraft("src/shop/Pager.java", "package shop;\npublic class Pager {\n" + body + "\n}\n"));
    }

    @Test
    @DisplayName("'추가:' 메서드는 시작 코드에 설계도 시그니처 그대로 틀로 있어야 한다: 없음·타입 다름·실제 구현은 실패하고 시작 코드 단계로 간다")
    void addedMethodsMustBeStubs() {
        var bp = withApi(List.of("기존: Pager.pages(int total, int size): int", "추가: Pager.rangeLabel(int page, int size, int total): String"));
        String stub = "public String rangeLabel(int page, int size, int total) {\n  throw new UnsupportedOperationException(\"TODO\");\n}";

        assertThat(CaseCheckService.stubs(bp, src("public int pages(int t, int s) { return t / s; }\n" + stub)).ok()).isTrue();
        var missing = CaseCheckService.stubs(bp, src("public int pages(int t, int s) { return t / s; }"));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.detail()).contains("rangeLabel(int, int, int) 가 시작 코드에 없습니다");
        assertThat(DevPipelineService.owners(missing, List.of())).containsExactly(TaskPipelineService.Stage.CODE);
        var wrongType = CaseCheckService.stubs(bp, src("public String rangeLabel(long page, int size, int total) { throw new UnsupportedOperationException(\"TODO\"); }"));
        assertThat(wrongType.detail()).contains("매개변수 타입이 다릅니다");
        var implemented = CaseCheckService.stubs(bp, src("public String rangeLabel(int page, int size, int total) { return page + \"-\" + size; }"));
        assertThat(implemented.detail()).contains("틀이 아니라 구현돼 있습니다");
        // 제네릭 매개변수도 비교된다
        var generic = withApi(List.of("추가: Pager.group(List<String> names, Map<String, Integer> sizes): int"));
        assertThat(CaseCheckService.stubs(generic, src("int group(List<String> n, Map<String, Integer> s) { throw new UnsupportedOperationException(\"TODO\"); }")).ok()).isTrue();
        assertThat(TaskPromptService.code("깊이")).contains("반드시 틀로 둔다").contains("UnsupportedOperationException(\"TODO\")").doesNotContain("없애거나");
    }

    @Test
    @DisplayName("다른 풀이·오답에 NOTES.md 같은 src/ 밖 파일이 섞여 나오면 src/ 파일만 남긴다(경로 오류로 헛돌지 않게)")
    void alternativeSolutionKeepsOnlySrcFiles() {
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.devBlueprint())
                .add(com.pair.domain.taskgen.dto.DevWritingDto.class, TaskPipelineServiceTest.devWriting())
                .add(com.pair.domain.taskgen.dto.DevCodeDto.class, TaskPipelineServiceTest.devCode())
                .add(com.pair.domain.taskgen.dto.DevAnswerDto.class, withNotes(TaskPipelineServiceTest.devAnswer()));
        var run = new DevPipelineService(ai, new com.pair.global.config.TaskGenProperties(null, null, null, null, null))
                .start(new com.pair.domain.taskgen.dto.TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20));
        DevDraftDto d = run.create(s -> {});

        assertThat(d.altSolutions().get(0).files()).extracting(DevDraftDto.FileDraft::path).containsOnly("src/shop/PaymentDiscount.java");
        assertThat(d.wrongFixes().get(0).files()).extracting(DevDraftDto.FileDraft::path).containsOnly("src/shop/PaymentDiscount.java");
    }

    private static com.pair.domain.taskgen.dto.DevAnswerDto withNotes(com.pair.domain.taskgen.dto.DevAnswerDto a) {
        var notes = new DevDraftDto.FileDraft("NOTES.md", "메모");
        var alts = a.altSolutions().stream().map(x -> new DevDraftDto.AltSolution(x.note(), java.util.stream.Stream.concat(x.files().stream(), java.util.stream.Stream.of(notes)).toList())).toList();
        var wrongs = a.wrongFixes().stream().map(x -> new DevDraftDto.WrongFix(x.reason(), java.util.stream.Stream.concat(x.files().stream(), java.util.stream.Stream.of(notes)).toList())).toList();
        return new com.pair.domain.taskgen.dto.DevAnswerDto(a.answer(), a.hiddenTests(), a.solution(), alts, wrongs);
    }

    // ── 설계도로 올리는 실패 경로 ─────────────────────────────

    private static com.pair.domain.taskgen.dto.GenCheckResultDto leak(String quote) {
        return new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [description] " + quote + " → 지운다");
    }

    @Test
    @DisplayName("관문이 지적한 문장이 설계도의 사실·근거 문장이면 글 단계가 아니라 설계도로 보낸다(이유 안내 포함). 설계도 밖의 문장이면 그대로")
    void gateOnBlueprintSentenceGoesToBlueprint() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var onBlueprint = leak(TaskPipelineServiceTest.F_SETTLE);
        var routed = StageRouteService.reroute(b, onBlueprint, List.of());

        assertThat(routed.name()).isEqualTo(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK);
        assertThat(routed.detail()).contains("기록이나 관측").contains(TaskPipelineService.LEAK_A_GATE).contains(TaskPipelineServiceTest.F_SETTLE);
        assertThat(DevPipelineService.owners(routed, List.of())).containsExactly(TaskPipelineService.Stage.BLUEPRINT);
        assertThat(BizPipelineService.owners(routed)).containsExactly(TaskPipelineService.Stage.BLUEPRINT);
        // 설계도에 없는 문장은 글 단계 탓
        var own = leak("원인을 찾아 고치세요 라고 작성자가 직접 쓴 문장");
        assertThat(StageRouteService.reroute(b, own, List.of())).isSameAs(own);
        // 관문이 아닌 검사는 문장이 겹쳐도 바꾸지 않는다
        var other = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.FACTS_CHECK, false, "M5 [x] " + TaskPipelineServiceTest.F_SETTLE + " → y");
        assertThat(StageRouteService.reroute(b, other, List.of())).isSameAs(other);
    }

    @Test
    @DisplayName("같은 검사가 같은 이유(같은 인용 또는 같은 상세)로 2번 연달아 실패하면 한 단계 위(설계도)로 올린다. 이유가 다르면 그대로")
    void sameFailureTwiceGoesUp() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var first = leak("작성자가 쓴 문장으로 설계도에는 없는 같은 위반 문장");
        var again = leak("작성자가 쓴 문장으로 설계도에는 없는 같은 위반 문장");
        var different = leak("완전히 다른 곳에서 새로 걸린 위반 문장입니다");

        assertThat(StageRouteService.reroute(b, again, List.of()).name()).isEqualTo(first.name());
        var up = StageRouteService.reroute(b, again, List.of(first));
        assertThat(up.name()).isEqualTo(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK);
        assertThat(up.detail()).contains("되풀이");
        assertThat(StageRouteService.reroute(b, different, List.of(first))).isSameAs(different);
        // 실행 검사처럼 인용이 없어도 상세가 같으면 같은 이유
        var run1 = new com.pair.domain.taskgen.dto.GenCheckResultDto("오답 검사", false, "오답 1 이 숨은 테스트를 모두 통과했습니다");
        assertThat(StageRouteService.reroute(b, run1, List.of(run1)).name()).isEqualTo(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK);
    }

    @Test
    @DisplayName("Run 이 직접: 설계도 문장이 걸린 관문 실패는 설계도부터 다시 돌리고, 같은 이유로 2번 걸리면 두 번째에 설계도로 올라간다")
    void runRestartsFromBlueprint() {
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.devBlueprint())
                .add(com.pair.domain.taskgen.dto.DevWritingDto.class, TaskPipelineServiceTest.devWriting())
                .add(com.pair.domain.taskgen.dto.DevCodeDto.class, TaskPipelineServiceTest.devCode())
                .add(com.pair.domain.taskgen.dto.DevAnswerDto.class, TaskPipelineServiceTest.devAnswer());
        var run = new DevPipelineService(ai, new com.pair.global.config.TaskGenProperties(null, null, null, null, null))
                .start(new com.pair.domain.taskgen.dto.TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20));
        run.create(s -> {});

        assertThat(run.restartsFrom(List.of(leak(TaskPipelineServiceTest.F_SETTLE)))).isTrue();
        var own = leak("작성자가 쓴 문장으로 설계도에는 없는 같은 위반 문장");
        assertThat(run.restartsFrom(List.of(own))).isFalse();
        ai.order.clear();
        run.redo(List.of(own), s -> {}); // 글 단계만
        assertThat(ai.order).doesNotContain(BlueprintDto.class);
        assertThat(run.restartsFrom(List.of(own))).isTrue(); // 같은 이유로 또 실패하면 설계도부터
        ai.order.clear();
        run.redo(List.of(own), s -> {});
        assertThat(ai.order).startsWith(BlueprintDto.class);
    }

    @Test
    @DisplayName("설계도 직후 점검: 글에 들어갈 문장이 위반이면 응시자 글을 쓰기 전에 설계도만 한 번 더 받고, 이유가 설계도 요청에 들어간다")
    void earlyBlueprintReview() {
        var bad = new com.pair.domain.taskgen.dto.ReviewResultDto(List.of(new com.pair.domain.taskgen.dto.ReviewResultDto.Violation("M5",
                TaskPipelineServiceTest.F_SETTLE, "basis", "기록이나 관측으로 다시 쓴다")));
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.devBlueprint())
                .add(com.pair.domain.taskgen.dto.ReviewResultDto.class, bad)
                .add(com.pair.domain.taskgen.dto.DevWritingDto.class, TaskPipelineServiceTest.devWriting())
                .add(com.pair.domain.taskgen.dto.DevCodeDto.class, TaskPipelineServiceTest.devCode())
                .add(com.pair.domain.taskgen.dto.DevAnswerDto.class, TaskPipelineServiceTest.devAnswer());
        var run = new DevPipelineService(ai, new com.pair.global.config.TaskGenProperties(null, null, null, null, null))
                .start(new com.pair.domain.taskgen.dto.TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20));
        run.create(s -> {});

        assertThat(ai.order).startsWith(BlueprintDto.class, com.pair.domain.taskgen.dto.ReviewResultDto.class, BlueprintDto.class,
                com.pair.domain.taskgen.dto.DevWritingDto.class);
        assertThat(ai.users.get(2)).contains("설계도가 아래 문제로 걸렸습니다").contains(TaskPipelineServiceTest.F_SETTLE);
        assertThat(run.usage()).extracting("stage").contains("REVIEW:BLUEPRINT");
        assertThat(run.usage().stream().filter(u -> u.stage().equals("REVIEW:BLUEPRINT")).findFirst().orElseThrow().model()).isEqualTo("gpt-6.1-sol"); // 설정의 관문 모델(DEV: sol)
    }

    @Test
    @DisplayName("BIZ 설계도: 한 문항의 정답 값이 다른 문항의 보기나 구할 값 문구에 있으면 걸린다")
    void optionValueConflicts() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var cases = new java.util.ArrayList<>(b.cases());
        cases.add(new BlueprintDto.Case("Q8", "상황", "100", "근거 문장입니다 하나 둘 셋 넷", "구할 값 가", List.of(), false));
        cases.add(new BlueprintDto.Case("Q9", "상황", "90", "근거 문장입니다 하나 둘 셋 넷 다섯", "구할 값 나", List.of("90", "100", "110"), false));
        var bp = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);

        assertThat(BlueprintService.optionConflicts(bp)).anyMatch(p -> p.contains("Q8") && p.contains("Q9") && p.contains("보기"));
        assertThat(BlueprintService.optionConflicts(b)).isEmpty();
        assertThat(BlueprintService.problems(bp, true)).anyMatch(p -> p.contains("보기에 있습니다"));
    }

    @Test
    @DisplayName("DEV 설계도: 판단 지점의 선택지 문장이 사례 근거나 예와 같으면 그 선택으로 정해져 걸린다. 선택지와 상관없는 사례는 통과")
    void judgmentPinnedByCaseOrExample() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        String optionFact = b.judgments().get(0).options().get(0).fact();
        var pinnedCase = new BlueprintDto.Case("pinned", "상황", "0.00", optionFact);
        var withPinned = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), List.of(b.cases().get(0), pinnedCase));
        assertThat(BlueprintService.pinnedJudgments(withPinned)).anyMatch(p -> p.contains("pinned"));
        assertThat(BlueprintService.problems(withPinned, false)).anyMatch(p -> p.contains("그 선택으로 결과가 정해집니다"));

        var pinnedExample = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(),
                List.of(new BlueprintDto.Example("입력 " + optionFact, "0.00")), b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(),
                b.docs(), b.questionPlan(), b.cases());
        assertThat(BlueprintService.pinnedJudgments(pinnedExample)).anyMatch(p -> p.contains("예("));
        assertThat(BlueprintService.pinnedJudgments(b)).isEmpty();
    }

    @Test
    @DisplayName("한자 검사는 응시자에게 안 보이는 칸(정답 키의 흔한 오답 사유 등)까지 모든 글 칸을 본다")
    void ideographsInHiddenFields() {
        var answer = new com.pair.domain.taskgen.dto.BizAnswerDto("## 필수 항목", List.of(new com.pair.domain.taskgen.dto.BizAnswerDto.Key("Q2", List.of("100"), "",
                List.of(), List.of(new com.pair.domain.taskgen.dto.BizDraftDto.WrongAnswer("100", "地域별 합계를 그대로 썼다")))));
        var r = TaskWritingService.hanCheck(Map.of("[정답]", StageCallService.json(answer)));
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("地域");
    }

    // ── 규칙 충돌 감사(C-1~C-7) 회귀 ─────────────────────────────

    @Test
    @DisplayName("C-1: '추가:' 메서드를 부르는 사례가 없는 설계도는 걸리고, 프롬프트 예시 설계도는 이 규칙을 지킨다. 결함 사례가 추가 메서드를 부르면 걸린다")
    void c1_addedMethodNeedsCase() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var noCase = withApi(List.of("기존: PaymentDiscount.totalDiscount(): BigDecimal", "추가: PaymentDiscount.payable(String price): BigDecimal"));
        assertThat(BlueprintService.addedMethodCases(noCase)).anyMatch(p -> p.contains("payable()") && p.contains("사례"));
        assertThat(BlueprintService.problems(noCase, false)).anyMatch(p -> p.contains("payable()"));

        var payableCase = new BlueprintDto.Case("payableAfterDiscount", "할인 1.25, 2.25 등록 뒤 payable(\"10.00\")", "6.50", "할인 1.25, 2.25 등록 뒤 payable(\"10.00\") → 6.50");
        var cases = new java.util.ArrayList<>(b.cases());
        cases.add(payableCase);
        var ok = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(),
                List.of("기존: PaymentDiscount.totalDiscount(): BigDecimal", "추가: PaymentDiscount.payable(String price): BigDecimal"), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);
        assertThat(BlueprintService.addedMethodCases(ok)).isEmpty();
        // 숨은 테스트 메서드 이름 검사는 이 사례를 허용한다(사례 name 과 같은 테스트 메서드)
        var tests = List.of(new DevDraftDto.FileDraft("test/shop/H.java", "class H { @Test void totalsExactly() {} @Test void payableAfterDiscount() { payable(\"10.00\"); } }"));
        assertThat(CaseCheckService.dev(ok, tests).ok()).isTrue();
        // 결함을 잡는 사례가 추가 메서드를 부르면(틀 예외로 항상 실패) 걸린다
        var flawCase = new BlueprintDto.Case("totalsExactly", "payable(\"10.00\") 합계", "0.30", "근거 문장 하나둘셋넷다섯");
        var bad = new BlueprintDto(ok.title(), ok.summary(), ok.code(), ok.category(), ok.competencies(), ok.scenario(), ok.api(), ok.facts(), ok.examples(), ok.flaws(),
                ok.judgments(), ok.trapKeywords(), ok.conclusion(), ok.tables(), ok.figures(), ok.docs(), ok.questionPlan(), List.of(flawCase, payableCase));
        assertThat(BlueprintService.addedMethodCases(bad)).anyMatch(p -> p.contains("결함을 잡는 사례"));
        // 프롬프트의 좋은 예
        String example = TaskPromptService.blueprint(false, 40, "깊이");
        assertThat(example).contains("payableAfterDiscount").contains("추가:").contains("\"추가:\" 메서드마다 그 메서드를 부르는 사례");
    }

    @Test
    @DisplayName("C-3: 테스트 근거 입력에 근거 종류(예/사실)가 붙고, 프롬프트는 사실 근거는 '끌어낼 수 있으면' 충분하다고 한다. 설계도 프롬프트도 같은 기준")
    void c3_backingAcceptsDerivableFact() {
        BlueprintDto b = TaskPipelineServiceTest.devBlueprint();
        var example = new BlueprintDto.Case("c1", "상황", "0.250", b.examples().get(0).input() + " → " + b.examples().get(0).expected());
        var fact = new BlueprintDto.Case("c2", "상황", "0.30", "7월 3일 접수 로그: 다른 입력의 기록");
        assertThat(TaskReviewService.basisKind(b, example)).isEqualTo("예");
        assertThat(TaskReviewService.basisKind(b, fact)).isEqualTo("사실");
        assertThat(TaskPromptService.check("backing-cases")).contains("근거 종류가 \"사실\"").contains("끌어낼 수 있으면 충분").contains("stage");
        assertThat(TaskPromptService.blueprint(false, 40, "깊이")).contains("끌어낼 수 있는 사실").contains("결함이 샌다");
    }

    @Test
    @DisplayName("C-4: 관문이 적은 단계(WRITING·BLUEPRINT·ANSWER·QUESTIONS)로 보낸다. 안 적으면 테스트 근거 WRITING, 정답 키 ANSWER, 다른 풀이 BLUEPRINT")
    void c4_gateRoutingByStage() {
        var backingMarked = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.BACKING_GATE, false, "M2 [hiddenTest:H x] 단정 줄 → 사례를 바꾼다 [단계:BLUEPRINT]");
        assertThat(DevPipelineService.owners(backingMarked, List.of())).containsExactly(TaskPipelineService.Stage.BLUEPRINT);
        var backingDefault = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.BACKING_GATE, false, "M2 [hiddenTest:H x] 단정 줄 → 예를 더한다");
        assertThat(DevPipelineService.owners(backingDefault, List.of())).containsExactly(TaskPipelineService.Stage.WRITING);
        var key = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.KEY_GATE, false, "M2 [question:2] 문구 → 정답 표시");
        assertThat(BizPipelineService.owners(key)).containsExactly(TaskPipelineService.Stage.ANSWER);
        var keyQuestions = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.KEY_GATE, false, "M2 [question:2] 문구 → 판단 지점 객관식 [단계:QUESTIONS]");
        assertThat(BizPipelineService.owners(keyQuestions)).containsExactly(TaskPipelineService.Stage.QUESTIONS);
        var alt = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.ALT_GATE, false, "M2 [hiddenTest:x] 실패 → 완화");
        assertThat(DevPipelineService.owners(alt, List.of())).containsExactly(TaskPipelineService.Stage.BLUEPRINT);
        // 점검 결과의 stage 가 상세에 [단계:..] 로 남는다
        var v = new com.pair.domain.taskgen.dto.ReviewResultDto.Violation("M2", "q", "hiddenTest:H x", "fix", "writing");
        assertThat(TaskReviewService.describe(List.of(v))).endsWith("[단계:WRITING]");
        // 위치 칸만 보고 라우팅한다(C-12): 고칠 방향 글의 '문항'에 끌려가지 않는다
        var leak = new com.pair.domain.taskgen.dto.GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [description] 규칙 문장 → 기획안의 문항 답에서 쓰지 않는다");
        assertThat(BizPipelineService.owners(leak)).containsExactly(TaskPipelineService.Stage.WRITING);
    }

    /** 정해 둔 테스트들이 모두 실패했다고 답하는 가짜 러너 */
    private static com.pair.infra.sandbox.CodeRunner failing(String... names) {
        return new com.pair.infra.sandbox.CodeRunner() {
            @Override
            public RunResult run(Map<String, String> sources) {
                return new RunResult(true, List.of(), 0, names.length, java.util.Arrays.stream(names).map(n -> new TestCase(n, false, "x")).toList(), List.of(), 0L);
            }

            @Override
            public BuildResult build(Map<String, String> sources) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static DevTaskGeneratorService altGen(String... failedTests) {
        return new DevTaskGeneratorService(null, new com.pair.global.config.TaskGenProperties(null, null, null, null, null), failing(failedTests), null, null);
    }

    @Test
    @DisplayName("C-4: 검수자의 다른 풀이가 공개 테스트에서 떨어지거나 결함을 안 고쳐 결함 테스트에서만 떨어지면 위반이 아니다. 다른 숨은 테스트에서 떨어지면 위반(설계도 단계)")
    void c4_altSolutionFlawIsNotAViolation() {
        DevDraftDto d = TaskGenFixtures.good(); // 숨은 테스트 shop.HiddenCartTest.couponOnce, 결함 caughtBy=couponOnce
        var alt = List.of(new DevDraftDto.FileDraft("src/shop/Cart.java", "package shop;\nclass Cart {}\n"));

        assertThat(altGen("shop.CartTest.basic").altRun(d, alt).violations()).isEmpty(); // 공개 테스트에서 떨어짐: 풀이 쪽 버그
        assertThat(altGen("shop.HiddenCartTest.couponOnce").altRun(d, alt).violations()).isEmpty(); // 결함을 안 고침
        var strict = altGen("shop.HiddenCartTest.couponOnce", "shop.HiddenCartTest.otherStrict").altRun(d, alt).violations();
        assertThat(strict).hasSize(1);
        assertThat(strict.get(0).quote()).contains("otherStrict").doesNotContain("couponOnce(");
        assertThat(strict.get(0).stage()).isEqualTo("BLUEPRINT");
    }

    @Test
    @DisplayName("C-5: 정답 키 관문에서 '판단 지점 문항의 모든 보기가 정답' 규칙을 없애고, 판단 지점 객관식 자체를 위반(문항 글 단계)으로 본다")
    void c5_judgmentMcqIsAViolation() {
        String p = TaskPromptService.check("answer-key");
        assertThat(p).doesNotContain("모든 보기가 정답이어야").contains("판단 지점을 객관식으로 물음").contains("QUESTIONS");
    }

    @Test
    @DisplayName("C-6: 정답 수치 노출 검사는 10 이하 작은 정수와 '근거 3개 이상'·'1페이지' 같은 필수 문구의 숫자를 정답 노출로 보지 않는다. 같은 값이 일반 문장에 있으면 여전히 걸린다")
    void c6_answerLeakIgnoresTemplateNumbers() {
        BizDraftDto good = BizFixtures.good("c6");
        // 정답이 3 인 단답형 + 요구사항에 "근거 3개 이상" 이 있어도 통과해야 한다
        var shortQ = good.questions().stream().filter(q -> q.type() == BizDraftDto.Type.SHORT).findFirst().orElseThrow();
        var small = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, shortQ.content(), shortQ.choices(), List.of("3"), shortQ.calc(), shortQ.sources(), shortQ.commonWrongAnswers());
        var withSmall = BizFixtures.edit(good, b -> b.requirements(List.of("결론과 자료 수치를 인용한 근거 3개 이상", "1페이지 기획안")).questions(replaceShort(good, small)));
        var leak = checkByName(withSmall, BizCheckService.LEAK);
        assertThat(leak.ok()).as(leak.detail()).isTrue();
        // 11 이상의 값이 일반 문장에 그대로 있으면 걸린다
        var big = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, shortQ.content(), shortQ.choices(), List.of("4400000"), shortQ.calc(), shortQ.sources(), shortQ.commonWrongAnswers());
        var leaked = BizFixtures.edit(good, b -> b.requirements(List.of("잔여현금은 4400000 이다")).questions(replaceShort(good, big)));
        assertThat(checkByName(leaked, BizCheckService.LEAK).ok()).isFalse();
    }

    private static List<BizDraftDto.QuestionDraft> replaceShort(BizDraftDto d, BizDraftDto.QuestionDraft with) {
        boolean[] done = {false};
        return d.questions().stream().map(q -> {
            if (!done[0] && q.type() == BizDraftDto.Type.SHORT) {
                done[0] = true;
                return with;
            }
            return q;
        }).toList();
    }

    private static com.pair.domain.taskgen.dto.GenCheckResultDto checkByName(BizDraftDto d, String name) {
        return BizCheckService.forDraft(new PdfService(), new BizFixtures.FakeSolver(), "claude-sonnet-5-5", d).stream()
                .filter(c -> c.name().equals(name)).findFirst().orElseThrow().run(d);
    }

    @Test
    @DisplayName("C-7: BIZ 설계도의 결함 caughtBy 에 '결론'을 쓸 수 있다(DEV 는 사례 name 이어야 함)")
    void c7_conclusionCaughtBy() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var flaw = b.flaws().get(0);
        var conclusion = new BlueprintDto.Flaw(flaw.what(), flaw.where(), flaw.evidence(), "결론");
        var bp = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(),
                List.of(conclusion), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases());
        assertThat(BlueprintService.problems(bp, true)).noneMatch(p -> p.contains("caughtBy"));
        assertThat(BlueprintService.problems(withFlaw(TaskPipelineServiceTest.devBlueprint(), new BlueprintDto.Flaw("a", "b", "c", "결론"), TaskPipelineServiceTest.devBlueprint().cases()), false))
                .anyMatch(p -> p.contains("caughtBy"));
    }

    // ── 라이브 6개 검증(g1)에서 나온 5건 회귀 ─────────────────────────────

    @Test
    @DisplayName("g1-1: 설계도 api 의 시그니처에 본문({ throw ... })이 붙어 있어도 뗀 뒤 비교한다. 정규화가 본문을 떼고, 본문이 남으면 설계도 검사가 거부한다. 불일치 메시지는 자르지 않는다")
    void g1_signatureWithBody() {
        String api = "추가: Reservation.isExpired(Instant now): boolean { throw new UnsupportedOperationException(\"TODO\"); }";
        Object[] sig = FeatureCheckService.addedSignature(api);
        assertThat(sig[0]).isEqualTo("isExpired");
        assertThat(sig[1]).isEqualTo(List.of("Instant"));
        var stub = List.of(new DevDraftDto.FileDraft("src/shop/Reservation.java", "package shop;\nclass Reservation {\n boolean isExpired(Instant now) { throw new UnsupportedOperationException(\"TODO\"); }\n}\n"));
        assertThat(FeatureCheckService.stubProblem(api, stub)).isNull();

        // 정규화가 본문을 떼고, 본문이 남은 설계도는 거부된다
        BlueprintDto b = withApi(List.of(api));
        assertThat(BlueprintService.problems(b, false)).anyMatch(p -> p.contains("시그니처만 쓰고"));
        assertThat(BlueprintService.normalized(b, false).api().get(0)).isEqualTo("추가: Reservation.isExpired(Instant now): boolean");

        // 불일치하면 설계도 문자열 전체와 시작 코드의 선언을 그대로 보여 준다
        var wrong = List.of(new DevDraftDto.FileDraft("src/shop/Reservation.java", "package shop;\nclass Reservation {\n boolean isExpired(java.time.Instant now, int grace) { throw new UnsupportedOperationException(\"TODO\"); }\n}\n"));
        String detail = FeatureCheckService.stubProblem("추가: Reservation.isExpired(Instant now): boolean", wrong);
        assertThat(detail).contains("설계도 시그니처 [추가: Reservation.isExpired(Instant now): boolean]").contains("isExpired(java.time.Instant, int)").doesNotContain("…");
    }

    @Test
    @DisplayName("g1-2: 추가한 메서드를 상수 반환으로 바꾼 가짜 구현(boolean 은 true·false 둘 다)을 정답 위에 얹어 숨은 테스트를 돌리고, 모두 통과하면 실패(설계도 단계). 만들 수 없는 반환 형은 건너뛴다")
    void g1_fakeImplementation() {
        String start = "package shop;\npublic class Res {\n  public boolean isExpired(int age) { throw new UnsupportedOperationException(\"TODO\"); }\n"
                + "  public void touch() { throw new UnsupportedOperationException(\"TODO\"); }\n}\n";
        String solved = "package shop;\npublic class Res {\n  public boolean isExpired(int age) { return age > 10; }\n  public void touch() { }\n}\n";
        DevDraftDto d = TaskGenFixtures.edit(TaskGenFixtures.good(), x -> x.files(List.of(new DevDraftDto.FileDraft("src/shop/Res.java", start)))
                .solution(List.of(new DevDraftDto.FileDraft("src/shop/Res.java", solved)))
                .hiddenTests(List.of(new DevDraftDto.FileDraft("test/shop/HiddenResTest.java", "package shop;\nclass HiddenResTest { @Test void a() {} }\n"))));

        var fakes = FakeImplService.fakes(d);
        assertThat(fakes).extracting(FakeImplService.Fake::method, FakeImplService.Fake::constant).containsExactly(
                org.assertj.core.groups.Tuple.tuple("isExpired", "true"), org.assertj.core.groups.Tuple.tuple("isExpired", "false")); // void touch() 는 건너뜀
        assertThat(fakes.get(0).content()).contains("return true;").doesNotContain("age > 10");
        assertThat(FakeImplService.constants("List<String>")).containsExactly("java.util.List.of()");
        assertThat(FakeImplService.constants("long")).containsExactly("0L");
        assertThat(FakeImplService.constants("void")).isEmpty();
        assertThat(FakeImplService.constants("Order")).isEmpty();

        // 가짜로 바꿔도 모든 테스트가 통과하면 문제, 하나라도 실패하면 통과
        var passAll = new com.pair.infra.sandbox.CodeRunner() {
            @Override
            public RunResult run(Map<String, String> sources) {
                return new RunResult(true, List.of(), 1, 0, List.of(new TestCase("shop.HiddenResTest.a", true, null)), List.of(), 0L);
            }

            @Override
            public BuildResult build(Map<String, String> sources) {
                throw new UnsupportedOperationException();
            }
        };
        assertThat(FakeImplService.problems(passAll, d)).hasSize(2).anyMatch(p -> p.contains("isExpired()") && p.contains("return true;"));
        assertThat(FakeImplService.problems(failing("shop.HiddenResTest.a"), d)).isEmpty();
        assertThat(DevPipelineService.owners(new com.pair.domain.taskgen.dto.GenCheckResultDto(DevCheckService.FAKE_IMPL, false, "x"), List.of()))
                .containsExactly(TaskPipelineService.Stage.BLUEPRINT);
        // 틀이 없는 과제(기존 코드만)는 확인할 가짜가 없어 영향 없음
        assertThat(FakeImplService.fakes(TaskGenFixtures.good())).isEmpty();
        assertThat(TaskPromptService.blueprint(false, 40, "깊이")).contains("참이 되는 사례와 거짓이 되는 사례");
    }

    @Test
    @DisplayName("g1-3: 설계도 프롬프트는 여러 조건 기준의 결합 방식을 사실로 적게 하고, 정답 키 관문은 자료(문서·CSV)를 받아 결합 방식이 적혀 있는지 본다")
    void g1_combinationWording() {
        assertThat(TaskPromptService.blueprint(true, 40, "깊이")).contains("모두 충족해야 통과한다").contains("결합");
        assertThat(TaskPromptService.check("answer-key")).contains("여러 조건 기준의 결합 방식이 자료에 없음").contains("모두 충족해야");
        var d = BizFixtures.good("g1");
        String in = TaskReviewService.bizInput(TaskReviewService.Check.ANSWER_KEY, TaskGenFixtures.form(), TaskPipelineServiceTest.bizBlueprint(), d);
        assertThat(in).contains("[응시자 자료(문서·CSV)]").contains(d.description().get(0));
    }

    @Test
    @DisplayName("g1-4: 설명·요구사항이 말한 파일(*.java, *.md, *.pdf, *.csv)이 보이는 파일에 없으면 실패하고 작성 단계로 간다. 있으면(경로 끝 일치) 통과, 확장자 없는 이름은 보지 않는다")
    void g1_mentionedFiles() {
        var visible = List.of("src/shop/Cart.java", "test/shop/CartTest.java", "자료/집행 확인서.pdf", "자료/실적.csv");
        assertThat(TaskWritingService.mentionedFilesCheck("Cart.java 와 집행 확인서.pdf, 실적.csv 를 보세요.", visible).ok()).isTrue();
        var bad = TaskWritingService.mentionedFilesCheck("NOTES.md 와 PublicChecks.java 를 확인하세요. 작업표와 검토 요청서도 봅니다. 버전 1.5 입니다.", visible);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).contains("NOTES.md").contains("PublicChecks.java").doesNotContain("작업표").doesNotContain("1.5");
        assertThat(DevPipelineService.owners(bad, List.of())).containsExactly(TaskPipelineService.Stage.WRITING);
        assertThat(BizPipelineService.owners(bad)).containsExactly(TaskPipelineService.Stage.WRITING);
    }

    @Test
    @DisplayName("g1-5: 문서에 사람의 동작만 서술하는 문장(펼쳤다, 정돈했다, 묶어 두었다, 메모에 옮겼다)이 있으면 경고(다시 만들지 않음). 사실 문장은 통과. 프롬프트도 금지")
    void g1_fillerSentences() {
        var clean = TaskWritingService.fillerCheck("담당자는 4월 수납액 5,000,000원을 확인했다.\n수수료는 10퍼센트였다.");
        assertThat(clean.ok()).isTrue();
        var warn = TaskWritingService.fillerCheck("담당자는 서류를 펼쳤다.\n담당자는 항목을 정돈했다.\n영수증을 묶어 두었다.\n값을 메모에 옮겼다.\n수수료는 10퍼센트였다.");
        assertThat(warn.ok()).isFalse();
        assertThat(warn.level()).isEqualTo(com.pair.domain.taskgen.dto.GenCheckResultDto.WARN);
        assertThat(warn.needsRegeneration()).isFalse();
        assertThat(warn.detail()).contains("4개").contains("펼쳤다").doesNotContain("수수료는 10퍼센트");
        assertThat(TaskPromptService.writingBiz(40)).contains("사실, 숫자, 판단, 요청 중 하나").contains("서류를 펼쳤다");
    }
}
