package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.TaskGenFixtures.f;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.PairProperties;
import com.pair.infra.sandbox.LocalJvmRunner;

/** 일부러 망가뜨린 Draft 에서 해당 검사만 실패하는지 */
class DevChecksTest {

    private final TaskGenFixtures.FakeRunner runner = new TaskGenFixtures.FakeRunner();

    private List<String> failedNames(DevDraft d) {
        return DevChecks.forDraft(runner, d).stream().map(c -> c.run(d)).filter(r -> !r.ok()).map(CheckResult::name).toList();
    }

    @Test
    @DisplayName("정상 Draft 는 검사 6개를 모두 통과하고, 같은 실행은 한 번만 돈다 (세 번)")
    void allPass() {
        assertThat(failedNames(TaskGenFixtures.good())).isEmpty();
        assertThat(runner.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("숨은 테스트가 시작 코드에서 통과하면 '숨은 테스트가 버그를 잡음' 만 실패한다")
    void hiddenDoesNotCatch() {
        runner.bugCaught = false;
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevChecks.HIDDEN_CATCHES);
    }

    @Test
    @DisplayName("정답이 숨은 테스트에 실패하면 '정답은 전부 통과' 만 실패한다")
    void solutionFails() {
        assertThat(failedNames(TaskGenFixtures.badSolution())).containsExactly(DevChecks.SOLVED_PASSES);
    }

    @Test
    @DisplayName("src 에 '// 버그' 주석이 있으면 '버그 힌트 주석 없음' 만 실패한다")
    void hintComment() {
        DevDraft d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/shop/Cart.java", "package shop;\npublic class Cart { int total() { return 1; } } // 버그: 여기\n"),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")));
        assertThat(failedNames(d)).containsExactly(DevChecks.NO_HINT);
    }

    @Test
    @DisplayName("경로 형식이 틀리면 형식이 실패하고 코드는 실행하지 않는다")
    void badPath() {
        DevDraft d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/../../evil/Cart.java", "package shop;\nclass Cart {}\n"),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")));
        assertThat(failedNames(d)).contains(DevChecks.FORMAT).doesNotContain(DevChecks.NO_HINT);
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("공개 테스트가 없거나 숨은 테스트가 test/ 밖이면 형식이 실패한다")
    void formatRules() {
        DevDraft noPublicTest = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", "package shop;\nclass Cart {}\n")));
        assertThat(DevRuns.formatProblem(noPublicTest)).contains("공개 테스트");
        DevDraft d = TaskGenFixtures.good();
        DevDraft hiddenOutside = new DevDraft(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(), d.answer(),
                d.files(), List.of(f("src/shop/Hidden.java", "package shop;\nclass Hidden {}\n")), d.solution());
        assertThat(DevRuns.formatProblem(hiddenOutside)).contains("test/");
    }

    @Test
    @DisplayName("실행이 30초를 넘으면 시간 검사만 실패한다")
    void slow() {
        runner.millis = 31_000;
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevChecks.TIME);
    }

    @Test
    @DisplayName("실제 LocalJvmRunner 로 작은 Java 예제를 끝까지 확인한다 (정상 → 모두 통과, 정답이 틀리면 정답 검사만 실패)")
    void realRunner() {
        var local = new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
        DevDraft d = new DevDraft("절댓값 오류", "절댓값 계산이 음수에서 틀립니다.", "abs-bug", "기능 구현",
                List.of("음수 입력에서 결과가 틀립니다."), List.of("고친다."), "# 정답\n음수면 부호를 바꾼다.",
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n"),
                        f("test/calc/CalcTest.java", "package calc;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport org.junit.jupiter.api.Test;\n"
                                + "class CalcTest { @Test void positive() { assertEquals(3, Calc.abs(3)); } }\n")),
                List.of(f("test/calc/HiddenCalcTest.java", "package calc;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport org.junit.jupiter.api.Test;\n"
                        + "class HiddenCalcTest { @Test void negative() { assertEquals(3, Calc.abs(-3)); } }\n")),
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n; } }\n")));
        var results = DevChecks.forDraft(local, d).stream().map(c -> c.run(d)).toList();
        assertThat(results).allMatch(CheckResult::ok, results.toString());

        DevDraft wrongSolution = TaskGenFixtures.withSolution(d, "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n");
        assertThat(DevChecks.forDraft(local, wrongSolution).stream().map(c -> c.run(wrongSolution)).filter(r -> !r.ok()).map(CheckResult::name))
                .containsExactly(DevChecks.SOLVED_PASSES);
    }
}
