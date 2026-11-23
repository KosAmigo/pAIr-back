package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import static com.pair.domain.taskgen.service.TaskGenFixtures.CART_SOLVED;
import static com.pair.domain.taskgen.service.TaskGenFixtures.CART_START;
import static com.pair.domain.taskgen.service.TaskGenFixtures.NOTES;
import static com.pair.domain.taskgen.service.TaskGenFixtures.edit;
import static com.pair.domain.taskgen.service.TaskGenFixtures.f;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.PairProperties;
import com.pair.infra.sandbox.LocalJvmRunner;

/** 일부러 망가뜨린 Draft 에서 해당 검사만 실패하는지 */
class DevCheckServiceTest {

    private final TaskGenFixtures.FakeRunner runner = new TaskGenFixtures.FakeRunner();

    private List<GenCheckResultDto> run(DevDraftDto d, TaskGenRequest form) {
        return DevCheckService.forDraft(runner, d, form).stream().map(c -> c.run(d)).toList();
    }

    private List<String> failedNames(DevDraftDto d) {
        return run(d, null).stream().filter(r -> !r.ok()).map(GenCheckResultDto::name).toList();
    }

    private GenCheckResultDto result(DevDraftDto d, String name, TaskGenRequest form) {
        return run(d, form).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static final List<DevDraftDto.FileDraft> VISIBLE = List.of(f("src/shop/Cart.java", CART_START),
            f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES));

    @Test
    @DisplayName("정상 Draft 는 검사 11개를 모두 통과하고, 같은 실행은 한 번만 돈다 (기본 3번 + 오답 2번)")
    void allPass() {
        var results = run(TaskGenFixtures.good(), TaskGenFixtures.form());
        assertThat(results).hasSize(11).allMatch(GenCheckResultDto::ok, results.toString());
        assertThat(results).extracting(GenCheckResultDto::level).containsOnly("fail", "warn");
        assertThat(results).filteredOn(r -> r.name().equals(DevCheckService.AMOUNT)).extracting(GenCheckResultDto::level).containsExactly("warn");
        assertThat(results).filteredOn(r -> !r.name().equals(DevCheckService.AMOUNT)).extracting(GenCheckResultDto::level).containsOnly("fail");
        assertThat(runner.calls.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("숨은 테스트가 시작 코드에서 통과하면 '숨은 테스트가 버그를 잡음' 만 실패한다")
    void hiddenDoesNotCatch() {
        runner.bugCaught = false;
        // 숨은 테스트가 아무것도 못 잡으니 오답도 모두 통과한다 → 오답 검사도 함께 실패
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevCheckService.HIDDEN_CATCHES, DevCheckService.WRONG_FIXES);
    }

    @Test
    @DisplayName("정답이 숨은 테스트에 실패하면 '정답은 전부 통과' 만 실패한다")
    void solutionFails() {
        assertThat(failedNames(TaskGenFixtures.badSolution())).containsExactly(DevCheckService.SOLVED_PASSES);
    }

    @Test
    @DisplayName("src 에 '// 버그' 주석이 있으면 '버그 힌트 주석 없음' 만 실패한다")
    void hintComment() {
        DevDraftDto d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/shop/Cart.java", CART_START.replace("int sum = 0;", "int sum = 0; // 버그: 여기")),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES)));
        assertThat(failedNames(d)).containsExactly(DevCheckService.NO_HINT);
    }

    @Test
    @DisplayName("경로 형식이 틀리면 형식이 실패하고 코드는 실행하지 않는다")
    void badPath() {
        DevDraftDto d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/../../evil/Cart.java", "package shop;\nclass Cart {}\n"),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")));
        assertThat(failedNames(d)).contains(DevCheckService.FORMAT).doesNotContain(DevCheckService.NO_HINT);
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("공개 테스트가 없거나 숨은 테스트가 test/ 밖이면 형식이 실패한다. NOTES.md·logs/ 는 자료로 허용, 다른 경로는 안 됨")
    void formatRules() {
        DevDraftDto noPublicTest = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", "package shop;\nclass Cart {}\n")));
        assertThat(DevRunService.formatProblem(noPublicTest)).contains("공개 테스트");
        DevDraftDto hiddenOutside = edit(TaskGenFixtures.good(), b -> b.hiddenTests(List.of(f("src/shop/Hidden.java", "package shop;\nclass Hidden {}\n"))));
        assertThat(DevRunService.formatProblem(hiddenOutside)).contains("test/");

        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("logs/app.log", "x"))))).isNull();
        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("../evil.md", "x"))))).contains("경로 형식");
        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("logs/../x.log", "x"))))).contains("경로 형식");
    }

    @Test
    @DisplayName("실행이 30초를 넘으면 시간 검사만 실패한다")
    void slow() {
        runner.millis = 31_000;
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevCheckService.TIME);
    }

    // ── 1. 함정 핵심어 노출 ─────────────────────

    @Test
    @DisplayName("핵심어가 설명·요구사항·보이는 파일 경로·코드 주석·메모에 있으면 '함정 핵심어 노출 없음' 만 실패한다 (대소문자·공백 무시)")
    void trapWordExposed() {
        var d = TaskGenFixtures.good();
        var inDescription = edit(d, b -> b.description(List.of("쿠폰이 중복  적용 되는 문제입니다.")));
        assertThat(failedNames(inDescription)).containsExactly(DevCheckService.TRAP_WORDS);
        assertThat(result(inDescription, DevCheckService.TRAP_WORDS, null).detail()).contains("설명").contains("중복 적용");

        var inRequirements = edit(d, b -> b.requirements(List.of("이중할인을 막는다.")));
        assertThat(result(inRequirements, DevCheckService.TRAP_WORDS, null).detail()).contains("요구사항");

        var inPath = edit(d, b -> b.filesWith(f("logs/이중할인.log", "x")));
        assertThat(result(inPath, DevCheckService.TRAP_WORDS, null).detail()).contains("보이는 파일 경로");

        var inComment = TaskGenFixtures.withFiles(d, List.of(f("src/shop/Cart.java", CART_START.replace("int sum = 0;", "int sum = 0; // 이중 할인 처리")),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES)));
        assertThat(result(inComment, DevCheckService.TRAP_WORDS, null).detail()).contains("보이는 코드 주석");

        var inNotes = TaskGenFixtures.withFiles(d, List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                f("NOTES.md", NOTES + "원인은 이중 할인으로 보임\n")));
        assertThat(result(inNotes, DevCheckService.TRAP_WORDS, null).detail()).contains("메모·로그");

        // 코드(주석이 아닌 곳)에 있는 말은 보지 않는다
        var inCode = TaskGenFixtures.withFiles(d, List.of(f("src/shop/Cart.java", CART_START.replace("int sum = 0;", "int 이중할인 = 0; int sum = 0;")),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES)));
        assertThat(result(inCode, DevCheckService.TRAP_WORDS, null).ok()).isTrue();
    }

    @Test
    @DisplayName("핵심어가 2개 미만이면 실패한다. 폼 함정 문장의 3글자 이상 말도 함께 본다 (2글자 이하·주제 말은 뺌)")
    void trapWordsFromForm() {
        var d = TaskGenFixtures.good();
        assertThat(failedNames(edit(d, b -> b.trapKeywords(List.of("중복 적용"))))).containsExactly(DevCheckService.TRAP_WORDS);
        assertThat(failedNames(edit(d, b -> b.trapKeywords(null)))).containsExactly(DevCheckService.TRAP_WORDS);

        assertThat(GenTextService.formWords("하루를 항상 24시간으로 계산", "일별 이용량 집계")).containsExactly("24시간");
        assertThat(GenTextService.formWords("쿠폰 두 장을 겹치면 할인이 중복 적용", "쿠폰 할인 계산")).containsExactly("겹치면");

        // 폼 문장에서 뽑은 '겹치면' 이 설명에 있으면 모델이 정한 핵심어가 아니어도 걸린다
        var form = TaskGenFixtures.form();
        var leaked = edit(d, b -> b.description(List.of("쿠폰을 겹치면 금액이 이상합니다.")));
        assertThat(result(leaked, DevCheckService.TRAP_WORDS, form).ok()).isFalse();
        assertThat(result(leaked, DevCheckService.TRAP_WORDS, null).ok()).isTrue();
    }

    // ── 2. 그럴듯한 오답 ─────────────────────

    @Test
    @DisplayName("오답이 숨은 테스트를 전부 통과하면 '그럴듯한 오답' 만 실패하고, 어떤 오답인지 적는다")
    void wrongFixPasses() {
        var passing = new DevDraftDto.WrongFix("날짜만 바꾼 풀이", List.of(f("src/shop/Cart.java", CART_SOLVED))); // FIXED 표시 = 가짜 러너가 모두 통과시킴
        var d = edit(TaskGenFixtures.good(), b -> b.wrongFixes(List.of(TaskGenFixtures.good().wrongFixes().get(0), passing)));
        assertThat(failedNames(d)).containsExactly(DevCheckService.WRONG_FIXES);
        String detail = result(d, DevCheckService.WRONG_FIXES, null).detail();
        assertThat(detail).contains("오답 2").contains("날짜만 바꾼 풀이").contains("숨은 테스트를 모두 통과").doesNotContain("오답 1");
    }

    @Test
    @DisplayName("오답 2~3개가 모두 숨은 테스트에서 실패하면 통과, 1개나 4개거나 이유·경로가 이상하면 실패")
    void wrongFixRules() {
        var good = TaskGenFixtures.good();
        assertThat(result(good, DevCheckService.WRONG_FIXES, null).ok()).isTrue();
        var one = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0))));
        assertThat(result(one, DevCheckService.WRONG_FIXES, null).detail()).contains("2~3개");
        assertThat(result(edit(good, b -> b.wrongFixes(null)), DevCheckService.WRONG_FIXES, null).ok()).isFalse();
        var four = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0), good.wrongFixes().get(1), good.wrongFixes().get(0), good.wrongFixes().get(1))));
        assertThat(result(four, DevCheckService.WRONG_FIXES, null).detail()).contains("3개까지");
        var noReason = edit(good, b -> b.wrongFixes(List.of(new DevDraftDto.WrongFix(" ", good.wrongFixes().get(0).files()), good.wrongFixes().get(1))));
        assertThat(result(noReason, DevCheckService.WRONG_FIXES, null).detail()).contains("reason");
        var testPath = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0),
                new DevDraftDto.WrongFix("x", List.of(f("test/shop/HiddenCartTest.java", "class X {}"))))));
        assertThat(result(testPath, DevCheckService.WRONG_FIXES, null).detail()).contains("src/");
        var traversal = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0),
                new DevDraftDto.WrongFix("x", List.of(f("src/../../evil/A.java", "class X {}"))))));
        assertThat(result(traversal, DevCheckService.WRONG_FIXES, null).detail()).contains("경로");
    }

    // ── 3. 분량 대비 시간 (warn) ─────────────────────

    @Test
    @DisplayName("정답 변경 줄 수(추가+삭제)를 센다: 한 줄 고치면 2줄, 빈 줄·앞뒤 공백은 무시")
    void changedLines() {
        assertThat(DevCheckService.changedLines(CART_START, CART_START)).isZero();
        assertThat(DevCheckService.changedLines(CART_START, CART_START.replace("sum += 1;", "sum += 9;"))).isEqualTo(2);
        assertThat(DevCheckService.changedLines("a\nb\n", "a\n\n  b  \n")).isZero();
        assertThat(DevCheckService.changedLines(null, "a\nb\n")).isEqualTo(2);
        assertThat(DevCheckService.changedLines("a\nb\nc\n", "a\nc\n")).isEqualTo(1);
    }

    @Test
    @DisplayName("정답 변경이 시간별 최소치(20분 2줄, 40분 6줄, 60분 12줄)보다 적으면 warn 으로 알리고, 이름만 실패 목록에 들어간다")
    void amountWarning() {
        var small = TaskGenFixtures.withSolution(TaskGenFixtures.good(), CART_START.replace("sum += 1;", "sum += 9; // FIXED"));
        var r60 = result(small, DevCheckService.AMOUNT, TaskGenFixtures.form());
        assertThat(r60.ok()).isFalse();
        assertThat(r60.level()).isEqualTo("warn");
        assertThat(r60.needsRegeneration()).isFalse();
        assertThat(r60.detail()).isEqualTo("정답 변경 2줄, 60분 과제치고 적음. 난이도·시간을 낮추거나 자료를 보강하세요");

        var form40 = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 중복 적용", "SELF", 40);
        var form20 = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 중복 적용", "SELF", 20);
        assertThat(result(small, DevCheckService.AMOUNT, form40).detail()).contains("2줄, 40분");
        assertThat(result(small, DevCheckService.AMOUNT, form20).ok()).isTrue(); // 20분은 2줄이면 충분

        var six = TaskGenFixtures.withSolution(TaskGenFixtures.good(), CART_START.replace("sum += 1;", "sum += 9;").replace("sum += 2;", "sum += 9;")
                .replace("sum += 3;", "sum += 9; // FIXED"));
        assertThat(result(six, DevCheckService.AMOUNT, form40).ok()).isTrue(); // 6줄
        assertThat(result(six, DevCheckService.AMOUNT, TaskGenFixtures.form()).ok()).isFalse(); // 60분은 12줄 필요
        // warn 이 있어도 fail 검사는 모두 통과한다
        assertThat(run(small, TaskGenFixtures.form()).stream().filter(GenCheckResultDto::needsRegeneration)).isEmpty();
    }

    // ── 4. 과제 종류별 필수 자료 ─────────────────────

    @Test
    @DisplayName("'장애' 과제에 로그·접수 메모가 없거나 200자 미만이면 '과제 종류별 필수 자료' 만 실패한다")
    void incidentNeedsMaterials() {
        var onlyCode = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")));
        assertThat(failedNames(onlyCode)).containsExactly(DevCheckService.MATERIALS);
        assertThat(result(onlyCode, DevCheckService.MATERIALS, null).detail()).contains("재현 로그나 접수 메모");

        var tooShort = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                f("NOTES.md", "# 메모\n쿠폰 두 장에서 금액이 이상함\n")));
        assertThat(failedNames(tooShort)).containsExactly(DevCheckService.MATERIALS);

        // logs/ 아래 파일이면 이름은 상관없고, 여러 파일의 글자 수를 합친다
        String line = "2026-11-20 10:01:02 WARN order=1042 coupons=2 total=3100 expected=6200 result=MISMATCH\n";
        var logs = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                f("logs/order.log", line + line), f("logs/payment.log", line)));
        assertThat(result(logs, DevCheckService.MATERIALS, null).ok()).isTrue();

        var incidentMd = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                f("INCIDENT.md", NOTES)));
        assertThat(result(incidentMd, DevCheckService.MATERIALS, null).ok()).isTrue();

        // 장애가 아닌 과제는 자료가 없어도 된다
        var feature = edit(onlyCode, b -> b.category("기능 구현"));
        assertThat(failedNames(feature)).isEmpty();
    }

    // ── 5. 후속 질문 ─────────────────────

    @Test
    @DisplayName("맞춤 후속 질문이 비었거나 핵심어·숨은 테스트 이름이 있으면 '후속 질문이 과제에 맞음' 만 실패한다")
    void followUp() {
        var d = TaskGenFixtures.good();
        assertThat(failedNames(edit(d, b -> b.taskFollowUp(" ")))).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(failedNames(edit(d, b -> b.taskFollowUp(null)))).containsExactly(DevCheckService.FOLLOW_UP);

        var withName = edit(d, b -> b.taskFollowUp("couponOnce 테스트가 통과한 이유를 설명해 주세요."));
        assertThat(failedNames(withName)).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(result(withName, DevCheckService.FOLLOW_UP, null).detail()).contains("couponOnce");

        var withKeyword = edit(d, b -> b.taskFollowUp("이중할인 문제를 어떻게 확인했나요?"));
        assertThat(failedNames(withKeyword)).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(result(withKeyword, DevCheckService.FOLLOW_UP, null).detail()).contains("이중 할인");
    }

    @Test
    @DisplayName("실제 LocalJvmRunner 로 작은 Java 예제를 끝까지 확인한다 (정상 → fail 검사 모두 통과, 정답이 틀리면 정답 검사만 실패)")
    void realRunner() {
        var local = new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
        String test = "package calc;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport org.junit.jupiter.api.Test;\n";
        DevDraftDto d = new DevDraftDto("절댓값 오류", "절댓값 계산이 음수에서 틀립니다.", "abs-bug", "기능 구현",
                List.of("음수 입력에서 결과가 틀립니다."), List.of("고친다."), "# 정답\n음수면 부호를 바꾼다.",
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n"),
                        f("test/calc/CalcTest.java", test + "class CalcTest { @Test void positive() { assertEquals(3, Calc.abs(3)); } }\n")),
                List.of(f("test/calc/HiddenCalcTest.java", test + "class HiddenCalcTest { @Test void negative() { assertEquals(3, Calc.abs(-3)); } }\n")),
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n; } }\n")),
                null, null, List.of("부호 반전", "마이너스"),
                List.of(new DevDraftDto.WrongFix("음수를 0 으로 바꾼다", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? 0 : n; } }\n"))),
                        new DevDraftDto.WrongFix("음수에 1 을 더한다", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? n + 1 : n; } }\n")))),
                "AI 가 고친 코드를 다른 입력으로도 확인했나요?");
        var results = DevCheckService.forDraft(local, d).stream().map(c -> c.run(d)).toList();
        assertThat(results.stream().filter(GenCheckResultDto::needsRegeneration)).isEmpty();
        assertThat(results.stream().filter(r -> !r.ok()).map(GenCheckResultDto::name)).containsExactly(DevCheckService.AMOUNT); // 분량 경고만

        DevDraftDto wrongSolution = TaskGenFixtures.withSolution(d, "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n");
        assertThat(DevCheckService.forDraft(local, wrongSolution).stream().map(c -> c.run(wrongSolution)).filter(GenCheckResultDto::needsRegeneration).map(GenCheckResultDto::name))
                .containsExactly(DevCheckService.SOLVED_PASSES);

        // 공개 테스트까지 깨지는 오답은 '숨은 테스트가 거른 것'이 아니므로 실패
        DevDraftDto publicOnly = edit(d, b -> b.wrongFixes(List.of(d.wrongFixes().get(0),
                new DevDraftDto.WrongFix("양수가 틀림", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n + 1; } }\n"))))));
        var r = DevCheckService.forDraft(local, publicOnly).stream().map(c -> c.run(publicOnly)).filter(x -> x.name().equals(DevCheckService.WRONG_FIXES)).findFirst().orElseThrow();
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("오답 2").contains("공개 테스트에서만");
    }
}
