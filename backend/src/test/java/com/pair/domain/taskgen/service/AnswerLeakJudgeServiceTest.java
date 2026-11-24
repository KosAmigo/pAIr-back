package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.LeakVerdictDto;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.AiUnavailableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M5 답 노출 AI 판정. AI 호출은 가짜로 바꿔 끼운다 */
class AnswerLeakJudgeServiceTest {

    private static final String LEAK = "원 단위 기대값은 가장 가까운 원으로 맞춘다.";

    /** 정해 둔 답이나 오류를 돌려주고 요청을 기록하는 가짜 AI */
    static class Fake implements TaskGenAiService {
        Object answer;
        final List<String> models = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        final List<String> systems = new ArrayList<>();

        @Override
        public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            return generate(model, system, user, type, 0, "");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
            models.add(model);
            users.add(user);
            systems.add(system);
            if (answer instanceof RuntimeException e) throw e;
            return new Output<>((T) answer, 100, 50);
        }
    }

    private final Fake ai = new Fake();
    private final AnswerLeakJudgeService judge = new AnswerLeakJudgeService(ai, new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, "gpt-6-luna", null));

    private static LeakVerdictDto leaks(String quote) {
        return new LeakVerdictDto(List.of(new LeakVerdictDto.Leak(quote, "반올림 결함", "계산 규칙을 알려 줌")));
    }

    @Test
    @DisplayName("E3 M3 v8 02: 요구사항이 숨은 결함을 피하는 결과를 규칙 문장으로 적으면 fail 이고, 판정 기준에 그 항목이 있다")
    void flawRuleInRequirementsFails() {
        String rule = "각 점수 구간의 이름은 알파벳 오름차순이다";
        ai.answer = leaks(rule);
        GenCheckResultDto r = judge.judge("[요구사항]\n- " + rule + "\n- 반환 건수는 n과 전체 건수 중 작은 값이다", "결함: 같은 점수 구간의 이름 정렬");

        assertThat(r.needsRegeneration()).isTrue();
        assertThat(r.detail()).contains(rule);
        assertThat(ai.systems.get(0)).contains("E3").contains("요구사항");
    }

    @Test
    @DisplayName("위반 문장이 있으면 fail 이고, 위반 문장과 결함이 detail 에 들어간다 (다시 생성 프롬프트에 쓰임)")
    void violationFails() {
        ai.answer = leaks(LEAK);
        GenCheckResultDto r = judge.judge("[요구사항]\n- " + LEAK, "결함: 반올림");

        assertThat(r.name()).isEqualTo(AnswerLeakJudgeService.NAME);
        assertThat(r.needsRegeneration()).isTrue();
        assertThat(r.detail()).contains(LEAK, "반올림 결함");
        assertThat(ai.models).containsExactly("gpt-6-luna");
        assertThat(ai.users.get(0)).contains(LEAK, "결함: 반올림");
    }

    @Test
    @DisplayName("위반이 없으면 통과한다")
    void passes() {
        ai.answer = new LeakVerdictDto(List.of());
        GenCheckResultDto r = judge.judge("[요구사항]\n- 예: 100원 → 100원", "결함: 반올림");

        assertThat(r.ok()).isTrue();
        assertThat(r.needsRegeneration()).isFalse();
    }

    @Test
    @DisplayName("판정 호출이 실패하면 warn 이고 다시 생성을 일으키지 않는다")
    void errorIsWarn() {
        ai.answer = new AiUnavailableException("시간 초과");
        GenCheckResultDto r = judge.judge("글", "결함");

        assertThat(r.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.needsRegeneration()).isFalse();
    }

    @Test
    @DisplayName("보이는 글에 없는 인용(모델이 지어낸 문장)은 버린다")
    void inventedQuoteIgnored() {
        ai.answer = leaks("글에 없는 문장");
        assertThat(judge.judge("[요구사항]\n- " + LEAK, "결함").ok()).isTrue();
    }

    @Test
    @DisplayName("DEV 생성기: 다른 검사가 통과하면 판정하고, 위반이면 fail 만 돌려준다. 다른 검사가 실패했으면 판정하지 않는다")
    void devAfterChecks() {
        var props = new TaskGenProperties(null, null, null, null, null, null, null, null, false, null, null);
        var gen = new DevTaskGeneratorService(ai, props, null, null, null);
        ai.answer = leaks("쿠폰 중복 적용");
        var d = TaskGenFixtures.edit(TaskGenFixtures.good(), b -> b.requirements(List.of("쿠폰 중복 적용")));

        var extra = gen.afterChecks(TaskGenFixtures.form(), d, List.of());
        assertThat(extra.checks()).hasSize(2); // M5 + 숫자 모순(같은 호출)
        assertThat(extra.checks().get(0).needsRegeneration()).isTrue();

        ai.users.clear();
        gen.afterChecks(TaskGenFixtures.form(), d, List.of(new GenCheckResultDto("형식", false, "x")));
        assertThat(ai.users).isEmpty();
    }

    private static final String ROW = "운영부,30,60,40,120,20";

    @Test
    @DisplayName("M1: 숫자 모순이 있으면 숫자 검사만 fail 이고 같은 호출 1번으로 판정한다(모순 숫자가 detail 에 들어감)")
    void contradictionFails() {
        ai.answer = new LeakVerdictDto(List.of(), List.of(new LeakVerdictDto.Contradiction(ROW, "재직 30명인데 참여자 60명")));
        var r = judge.judgeAll("[CSV]\n부서,재직,참여\n" + ROW, "결함");

        assertThat(ai.models).hasSize(1);
        assertThat(r.get(0).ok()).isTrue();
        assertThat(r.get(1).name()).isEqualTo(AnswerLeakJudgeService.NUMBERS);
        assertThat(r.get(1).needsRegeneration()).isTrue();
        assertThat(r.get(1).detail()).contains(ROW, "참여자 60명");
    }

    @Test
    @DisplayName("M1: 정상 자료는 통과하고, 지어낸 인용과 호출 오류는 각각 통과·warn 이다")
    void contradictionPassAndError() {
        ai.answer = new LeakVerdictDto(List.of(), List.of());
        assertThat(judge.judgeAll("글", "결함").get(1).ok()).isTrue();
        ai.answer = new LeakVerdictDto(List.of(), List.of(new LeakVerdictDto.Contradiction("글에 없는 줄", "x")));
        assertThat(judge.judgeAll("글", "결함").get(1).ok()).isTrue();
        ai.answer = new AiUnavailableException("시간 초과");
        var r = judge.judgeAll("글", "결함");
        assertThat(r).allMatch(x -> GenCheckResultDto.WARN.equals(x.level()) && !x.needsRegeneration());
    }

    @Test
    @DisplayName("M5 오탐 방지: 판정 기준에 작업 지시·판단 지점 문구·증상이 허용으로 적혀 있고, 02의 지시·판단 지점은 모델이 위반 없음으로 답하면 통과한다")
    void v7FalsePositivesAllowed() {
        ai.answer = new LeakVerdictDto(List.of());
        var r = judge.judge("먼저 기존 합계를 수정하고, 이어서 totalFor 기능을 구현하세요.\ntotalFor(\"missing\") → 0 또는 IllegalArgumentException", "결함: int 오버플로");
        assertThat(r.ok()).isTrue();
        assertThat(ai.systems.get(0)).contains("작업 지시", "판단 지점", "증상", "원인·위치·고치는 법·계산 방법");
    }
}
