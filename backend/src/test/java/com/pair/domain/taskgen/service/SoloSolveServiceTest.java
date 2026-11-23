package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.TaskGenFixtures.f;
import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloAnswerDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.AiUnavailableException;
import com.pair.infra.sandbox.LocalJvmRunner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 가짜 AI + 실제 LocalJvmRunner 로 "AI 혼자 바로 풀리지 않음" 검사의 판정을 확인한다 (실제 AI 호출 없음) */
class SoloSolveServiceTest {

    private static final String TEST = "package calc;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport org.junit.jupiter.api.Test;\n";
    private static final String RIGHT = "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n; } }\n";
    private static final String WRONG = "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n";
    private static final String BROKEN = "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : ; } }\n";

    /** 준비한 답(SoloAnswerDto)이나 오류를 차례로 돌려주는 가짜 AI. 요청 전체를 기록한다 */
    static class FakeAi implements TaskGenAiService {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> models = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        final List<String> efforts = new ArrayList<>();

        @Override
        public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            return generate(model, system, user, type, 0, "");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
            models.add(model);
            systems.add(system);
            users.add(user);
            efforts.add(effort);
            Object next = script.poll();
            if (next instanceof RuntimeException e) throw e;
            if (next == null) throw new AiUnavailableException("준비된 답이 없습니다.");
            return new Output<>((T) next, 100, 50);
        }
    }

    private final FakeAi ai = new FakeAi();
    private final LocalJvmRunner runner = new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));

    private static PairProperties pair() {
        return new PairProperties(null, new PairProperties.Assistant("openai", "gpt-6-luna", 8000, "low", 40, 1000, "시스템 문구"),
                null, null, null, null, null);
    }

    private SoloSolveService service(TaskGenProperties props) {
        return new SoloSolveService(ai, props, pair(), runner);
    }

    private SoloSolveService service() {
        return service(new TaskGenProperties(null, null, null, null, null));
    }

    private static DevDraftDto draft() {
        return new DevDraftDto("절댓값 오류", "절댓값 계산이 음수에서 틀립니다.", "abs-bug", "기능 구현",
                List.of("음수 입력에서 결과가 틀립니다."), List.of("고친다."), "# 정답\n음수면 부호를 바꾼다.",
                List.of(f("src/calc/Calc.java", WRONG),
                        f("test/calc/CalcTest.java", TEST + "class CalcTest { @Test void positive() { assertEquals(3, Calc.abs(3)); } }\n")),
                List.of(f("test/calc/HiddenCalcTest.java", TEST + "class HiddenCalcTest { @Test void negative() { assertEquals(3, Calc.abs(-3)); } }\n")),
                List.of(f("src/calc/Calc.java", RIGHT)),
                null, null, List.of("부호 반전", "마이너스"),
                List.of(new DevDraftDto.WrongFix("음수를 0 으로 바꾼다", List.of(f("src/calc/Calc.java", WRONG)))),
                "AI 가 고친 코드를 다른 입력으로도 확인했나요?");
    }

    private static SoloAnswerDto answer(String content) {
        return new SoloAnswerDto(List.of(f("src/calc/Calc.java", content)));
    }

    @Test
    @DisplayName("2번 모두 통과하면 warn 이고 ok=false, 풀이 파일과 토큰을 남긴다")
    void bothSolved() {
        ai.script.add(answer(RIGHT));
        ai.script.add(answer(RIGHT));
        var r = service().run(draft());

        assertThat(r.check().ok()).isFalse();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.check().needsRegeneration()).isFalse();
        assertThat(r.check().detail()).isEqualTo("응시자 AI(gpt-6-luna)가 설명만 보고 2번 모두 바로 풀었어요. 함정을 깊게 하거나 자가진단용으로 쓰세요");
        assertThat(r.files()).containsOnlyKeys("1/src/calc/Calc.java", "2/src/calc/Calc.java");
        assertThat(r.inputTokens()).isEqualTo(200);
        assertThat(r.outputTokens()).isEqualTo(100);
        assertThat(ai.models).containsExactly("gpt-6-luna", "gpt-6-luna");
        assertThat(ai.efforts).containsOnly("low");
        assertThat(ai.systems).containsOnly("시스템 문구");
    }

    @Test
    @DisplayName("한 번이라도 숨은 테스트에서 실패하면 ok (2번 중 1번)")
    void oneFailsHidden() {
        ai.script.add(answer(RIGHT));
        ai.script.add(answer(WRONG));
        var r = service().run(draft());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.check().detail()).isEqualTo("2번 중 1번 숨은 테스트에서 실패(검증이 필요한 과제)");
    }

    @Test
    @DisplayName("컴파일 안 되는 답은 실패로 센다 (2번 중 1번)")
    void compileFailure() {
        ai.script.add(answer(BROKEN));
        ai.script.add(answer(RIGHT));
        var r = service().run(draft());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().detail()).contains("2번 중 1번 숨은 테스트에서 실패");
    }

    @Test
    @DisplayName("테스트 파일을 덮어쓰거나 폴더 밖으로 나가는 경로는 버려서 통과로 치지 않는다")
    void badPathsIgnored() {
        var sneaky = new SoloAnswerDto(List.of(f("test/calc/HiddenCalcTest.java", TEST + "class HiddenCalcTest { @Test void x() {} }\n"),
                f("../Evil.java", "x")));
        ai.script.add(sneaky);
        ai.script.add(sneaky);
        var r = service().run(draft());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().detail()).contains("2번 중 2번 숨은 테스트에서 실패");
        assertThat(r.files()).isEmpty();
    }

    @Test
    @DisplayName("AI 호출이 실패하면 이 검사만 ok=true, warn (호출 실패)")
    void aiError() {
        ai.script.add(new AiUnavailableException("끊김"));
        ai.script.add(new AiUnavailableException("끊김"));
        var r = service().run(draft());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.check().detail()).isEqualTo("AI 단독 풀이를 확인하지 못했어요(호출 실패)");
        assertThat(r.inputTokens()).isZero();
    }

    @Test
    @DisplayName("한 번은 통과, 한 번은 호출 실패이면 확인하지 못한 것으로 본다")
    void oneSolvedOneError() {
        ai.script.add(answer(RIGHT));
        ai.script.add(new AiUnavailableException("끊김"));
        var r = service().run(draft());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().detail()).contains("호출 실패");
    }

    @Test
    @DisplayName("solo-model 을 따로 정하면 그 모델을 쓰고, 시도 횟수 설정을 따른다")
    void configured() {
        ai.script.add(answer(WRONG));
        var props = new TaskGenProperties(null, null, null, null, null, null, null, null, true, "gpt-6-mini", 1);
        var r = service(props).run(draft());

        assertThat(ai.models).containsExactly("gpt-6-mini");
        assertThat(r.check().detail()).isEqualTo("1번 중 1번 숨은 테스트에서 실패(검증이 필요한 과제)");
    }

    @Test
    @DisplayName("AI 에 보내는 글에는 설명·요구사항·보이는 파일만 있고 숨은 테스트·정답·오답·함정 정보는 없다")
    void promptHasNoSecrets() {
        ai.script.add(answer(WRONG));
        ai.script.add(answer(WRONG));
        service().run(draft());

        assertThat(ai.users).hasSize(2);
        String u = ai.users.get(0);
        assertThat(u).contains("음수 입력에서 결과가 틀립니다.", "고친다.", "src/calc/Calc.java", "test/calc/CalcTest.java", "positive",
                "이 문제를 고쳐 주세요. 고친 src 파일의 전체 내용을 주세요.");
        assertThat(u).doesNotContain("HiddenCalcTest", "negative", "hiddenTests", "음수면 부호를 바꾼다", "n < 0 ? -n : n",
                "부호 반전", "마이너스", "음수를 0 으로 바꾼다", "다른 입력으로도 확인", "wrongFixes", "trapKeywords");
        assertThat(ai.systems.get(0)).doesNotContain("HiddenCalcTest", "negative", "음수면 부호를 바꾼다");
    }

    @Test
    @DisplayName("solo-enabled=false 이면 검사를 돌리지 않고 report 에 건너뜀을 남긴다 (AI 호출 없음)")
    void disabled() {
        var props = new TaskGenProperties(null, null, null, null, null, null, null, null, false, null, null);
        var gen = new DevTaskGeneratorService(ai, props, runner, null, service(props));
        var extra = gen.afterChecks(TaskGenFixtures.form(), draft(), List.of());

        assertThat(ai.users).isEmpty();
        assertThat(extra.checks()).hasSize(1);
        assertThat(extra.checks().get(0).name()).isEqualTo(SoloSolveService.NAME);
        assertThat(extra.checks().get(0).ok()).isTrue();
        assertThat(extra.checks().get(0).detail()).startsWith("건너뜀");
        assertThat(extra.files()).isEmpty();
    }

    @Test
    @DisplayName("다른 fail 검사가 실패한 Draft 에는 돌리지 않는다")
    void skippedWhenFailed() {
        var gen = new DevTaskGeneratorService(ai, new TaskGenProperties(null, null, null, null, null), runner, null, service());
        var failed = new GenCheckResultDto("형식", false, "x");
        var extra = gen.afterChecks(new TaskGenRequest("DEV", "주제", "함정", "SELF", 60), draft(), List.of(failed));

        assertThat(ai.users).isEmpty();
        assertThat(extra.checks().get(0).detail()).startsWith("건너뜀");
    }
}
