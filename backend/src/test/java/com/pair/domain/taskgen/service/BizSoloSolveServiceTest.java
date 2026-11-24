package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.SoloBizAnswerDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.PairProperties;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.AiUnavailableException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M5(BIZ) "AI 답을 그대로 내면 함정에 걸림" 검사의 판정 규칙 (가짜 AI, 실제 호출 없음) */
class BizSoloSolveServiceTest {

    /** 준비한 답이나 오류를 차례로 돌려주는 가짜 AI. 요청 전체를 기록한다 */
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
            return new Output<>((T) next, 3000, 200);
        }
    }

    private final FakeAi ai = new FakeAi();

    private BizSoloSolveService service(TaskGenProperties props) {
        var pair = new PairProperties(null, new PairProperties.Assistant("openai", "gpt-6-luna", 8000, "low", 40, 1000, "시스템 문구"),
                null, null, null, null, null);
        return new BizSoloSolveService(ai, props, pair);
    }

    private BizSoloSolveService service() {
        return service(new TaskGenProperties(null, null, null, null, null));
    }

    /** BizFixtures.good 의 정답은 1, 2, 21080, 2000. 함정 문항은 단답형 Q3, Q4 */
    private static SoloBizAnswerDto answers(String... a) {
        return new SoloBizAnswerDto(List.of(a));
    }

    /** 첫 객관식을 결론 문항(정답 보기=correctConclusion, 함정 보기=wrongConclusion)으로 바꾼 Draft */
    private static BizDraftDto withConclusionQuestion() {
        BizDraftDto d = BizFixtures.good("biz-solo");
        var q = BizFixtures.single("라이트 요금제를 어떻게 하는 것이 좋은가?",
                List.of("라이트 요금제를 전면 출시한다", "라이트 요금제를 시범 출시한다", "출시하지 않는다", "판단을 미룬다"), 2,
                BizFixtures.DOC1 + "|2", BizFixtures.DOC2 + "|1");
        return BizFixtures.edit(d, b -> b.questions(BizFixtures.replaceQuestion(d, 0, q)));
    }

    @Test
    @DisplayName("(a) LIVE·SELF 모두: AI 가 함정 문항(단답형)을 모두 맞히면 fail 이라 다시 생성 대상이고, AI 답과 단서 문장과 다시 설계 지시가 들어간다")
    void solvedLive() {
        ai.script.add(answers("1", "2", "21080", "2000"));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().name()).isEqualTo("AI 답을 그대로 내면 함정에 걸림");
        assertThat(r.check().ok()).isFalse();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.check().needsRegeneration()).isTrue();
        assertThat(r.check().detail()).contains("응시자 AI(gpt-6-luna)", "그대로 내도 통과", "Q3(AI 답 21080, 정답 21080)", "Q4(AI 답 2000, 정답 2000)")
                .contains("AI 가 단서로 썼을 만한 자료 문장: " + BizFixtures.EVIDENCE).contains("다시 설계 지시");
        assertThat(r.answers()).containsExactly("1", "2", "21080", "2000");
        assertThat(r.inputTokens()).isEqualTo(3000);
        assertThat(r.outputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("(b) 함정 문항을 하나라도 틀리면 ok (AI 가 함정에 빠진 값을 냄)")
    void trapMissed() {
        ai.script.add(answers("1", "2", "23560", "2000"));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().needsRegeneration()).isFalse();
        assertThat(r.check().detail()).contains("함정 문항에서 틀렸어요").contains("Q3(AI 답 23560, 정답 21080)").doesNotContain("Q4");
    }

    @Test
    @DisplayName("(b) 결론 객관식은 보기가 trap 결론 문구와 같으면 함정 문항이다: 단답형은 맞혀도 결론을 틀리면 ok")
    void conclusionQuestionMissed() {
        ai.script.add(answers("1", "2", "21080", "2000")); // Q1 정답은 2번(시범 출시), AI 는 1번(전면 출시)
        var r = service().run(withConclusionQuestion());

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().detail()).contains("Q1(AI 답 1, 정답 2)");
    }

    @Test
    @DisplayName("결론 객관식까지 모두 맞히면 fail")
    void conclusionQuestionSolved() {
        ai.script.add(answers("2", "2", "21080", "2000"));
        var r = service().run(withConclusionQuestion());

        assertThat(r.check().needsRegeneration()).isTrue();
        assertThat(r.check().detail()).contains("함정 문항 3개를 모두 맞혔어요").contains("Q1(AI 답 2, 정답 2)");
    }

    @Test
    @DisplayName("함정과 무관한 객관식은 틀려도 함정 문항으로 세지 않는다 (단답형을 모두 맞히면 fail)")
    void plainSingleIgnored() {
        ai.script.add(answers("4", "4", "21080", "2000"));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().needsRegeneration()).isTrue();
        assertThat(BizSoloSolveService.trapQuestions(BizFixtures.good("biz-solo"))).containsExactly(2, 3);
        assertThat(BizSoloSolveService.trapQuestions(withConclusionQuestion())).containsExactly(0, 2, 3);
    }

    @Test
    @DisplayName("답이 모자라면 빠진 함정 문항은 틀린 것으로 센다")
    void missingAnswerIsWrong() {
        ai.script.add(answers("1", "2", "21080"));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().detail()).contains("Q4(AI 답 null, 정답 2000)");
    }

    @Test
    @DisplayName("(c) AI 호출이 실패하면 ok=true, warn 이라 생성을 막지 않는다")
    void aiError() {
        ai.script.add(new AiUnavailableException("끊김"));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.check().needsRegeneration()).isFalse();
        assertThat(r.check().detail()).isEqualTo("AI 단독 풀이를 확인하지 못했어요(호출 실패)");
        assertThat(r.inputTokens()).isZero();
        assertThat(r.answers()).isEmpty();
    }

    @Test
    @DisplayName("(c) 답이 비어서 오면 확인하지 못한 것으로 보고 warn")
    void emptyAnswers() {
        ai.script.add(new SoloBizAnswerDto(List.of()));
        var r = service().run(BizFixtures.good("biz-solo"));

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.check().detail()).contains("확인하지 못했어요");
    }

    @Test
    @DisplayName("응시 화면과 같은 모델·추론 강도·시스템 문구로 1번만 부르고, 정답·계산식·체크리스트·함정 정보는 보내지 않는다")
    void promptHasNoSecrets() {
        ai.script.add(answers("1", "2", "21080", "2000"));
        service().run(BizFixtures.good("biz-solo"));

        assertThat(ai.users).hasSize(1);
        assertThat(ai.models).containsExactly("gpt-6-luna");
        assertThat(ai.efforts).containsExactly("low");
        assertThat(ai.systems).containsExactly("시스템 문구");
        String u = ai.users.get(0);
        assertThat(u).contains("영상 구독 서비스의 이탈률이 올랐습니다.", "결론과 근거 3개를 쓴다.", "[자료 자료/설문 요약.pdf 1쪽]", "[자료 자료/매출.csv]",
                "2026-10,베이직,62000,9900", "Q3. (단답형)", "Q1. (객관식)", "  2) 38%", BizSoloSolveService.REQUEST);
        assertThat(u).doesNotContain("round(v(", "참고 계산", "체크리스트", "commonWrongAnswers", "23560", "설문 의향 38%를 그대로 곱함",
                "라이트 요금제를 시범 출시한다", "전환율", "의향 보정", "정답");
    }

    @Test
    @DisplayName("solo-model 을 따로 정하면 그 모델을 쓴다")
    void configuredModel() {
        ai.script.add(answers("1", "2", "1", "1"));
        service(new TaskGenProperties(null, null, null, null, null, null, null, null, true, "gpt-6-mini", 1)).run(BizFixtures.good("biz-solo"));

        assertThat(ai.models).containsExactly("gpt-6-mini");
    }

    // ── 생성기 연결 (afterChecks) ─────────────────

    private BizTaskGeneratorService generator(TaskGenProperties props) {
        return new BizTaskGeneratorService(ai, props, null, null, null, service(props));
    }

    private static TaskGenRequest form(String usage) {
        return new TaskGenRequest("BIZ", "저가 요금제 출시 여부", "설문 의향을 실제 전환율로 보정하지 않음", usage, 60);
    }

    @Test
    @DisplayName("(d) 다른 fail 검사가 실패했으면 돌리지 않는다 (AI 호출 없음, 건너뜀)")
    void skippedWhenOtherCheckFailed() {
        var gen = generator(new TaskGenProperties(null, null, null, null, null));
        var failed = new GenCheckResultDto(BizCheckService.CALC, false, "계산 불일치");
        var extra = gen.afterChecks(form("LIVE"), BizFixtures.good("biz-solo"), List.of(failed));

        assertThat(ai.users).isEmpty();
        assertThat(extra.checks()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo(BizSoloSolveService.NAME);
            assertThat(c.ok()).isTrue();
            assertThat(c.detail()).startsWith("건너뜀");
        });
    }

    @Test
    @DisplayName("(d) warn 만 있는 결과에서는 돌린다")
    void runsWhenOnlyWarn() {
        ai.script.add(answers("1", "2", "21080", "2000"));
        var gen = generator(new TaskGenProperties(null, null, null, null, null));
        var warn = new GenCheckResultDto(BizCheckService.EVIDENCE_STYLE, false, "해석 지시", GenCheckResultDto.WARN);
        var extra = gen.afterChecks(form("LIVE"), BizFixtures.good("biz-solo"), List.of(warn));

        assertThat(ai.users).hasSize(1);
        assertThat(extra.checks()).singleElement().satisfies(c -> assertThat(c.needsRegeneration()).isTrue());
        assertThat(extra.details()).containsKeys("soloTokens", "soloAnswers");
    }

    @Test
    @DisplayName("(a) 용도 SELF 여도 같은 경우 fail (M5 는 필수)")
    void selfUsageFails() {
        ai.script.add(answers("1", "2", "21080", "2000"));
        var extra = generator(new TaskGenProperties(null, null, null, null, null)).afterChecks(form("SELF"), BizFixtures.good("biz-solo"), List.of());

        assertThat(extra.checks()).singleElement().satisfies(c -> {
            assertThat(c.ok()).isFalse();
            assertThat(c.needsRegeneration()).isTrue();
        });
    }

    @Test
    @DisplayName("solo-enabled=false 이면 돌리지 않고 건너뜀을 남긴다")
    void disabled() {
        var props = new TaskGenProperties(null, null, null, null, null, null, null, null, false, null, null);
        var extra = generator(props).afterChecks(form("LIVE"), BizFixtures.good("biz-solo"), List.of());

        assertThat(ai.users).isEmpty();
        assertThat(extra.checks().get(0).detail()).startsWith("건너뜀");
    }
}
