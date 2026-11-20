package com.pair.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 문항 채점 규칙 (AI 없이 코드로만) */
class QuestionGradingTest {

    private static boolean ok(String answer, String... accepted) {
        return ShortAnswerMatcher.matches(answer, List.of(accepted));
    }

    @Test
    void 숫자_답은_쉼표_공백_전각_단위를_무시하고_값으로_비교한다() {
        assertThat(ok("8,430", "8430")).isTrue();
        assertThat(ok("8430만 원", "8430")).isTrue();
        assertThat(ok(" 8 430 ", "8430")).isTrue();
        assertThat(ok("８４３０", "8430")).isTrue();
        assertThat(ok("8430.0", "8430")).isTrue();
        assertThat(ok("12.5%", "12.5")).isTrue();
        assertThat(ok("-3명", "-3")).isTrue();
        assertThat(ok("8,430", "8,430")).isTrue(); // 인정 답안에 쉼표가 있어도 같은 방식으로 정리한다
    }

    @Test
    void 틀린_값과_숫자가_아닌_답은_오답이다() {
        assertThat(ok("8431", "8430")).isFalse();
        assertThat(ok("84300", "8430")).isFalse();
        assertThat(ok("약 팔천", "8430")).isFalse();
        assertThat(ok("만 원 8430", "8430")).isFalse(); // 맨 앞이 숫자가 아니면 읽지 않는다
        assertThat(ok("", "8430")).isFalse();
        assertThat(ok(null, "8430")).isFalse();
    }

    @Test
    void 숫자가_아닌_인정_답안은_대소문자를_무시하고_비교한다() {
        assertThat(ok("Basic 플랜", "basic플랜")).isTrue();
        assertThat(ok("프리미엄", "베이직")).isFalse();
    }

    @Test
    void 인정_답안_중_하나라도_맞으면_정답이다() {
        assertThat(ok("20", "10", "20", "30")).isTrue();
        assertThat(ok("40", "10", "20", "30")).isFalse();
        assertThat(ok("스탠다드", "10", "스탠다드")).isTrue();
    }

    @Test
    void 단답형_채점_이유() {
        assertThat(QuestionService.gradeShort(3, "8,430만 원", List.of("8430")))
                .isEqualTo(new QuestionService.Graded(3, true, "정답"));
        assertThat(QuestionService.gradeShort(3, "7000", List.of("8430")))
                .isEqualTo(new QuestionService.Graded(0, false, "오답 (답: 7000 / 정답: 8430)"));
        assertThat(QuestionService.gradeShort(3, "  ", List.of("8430"))).isEqualTo(new QuestionService.Graded(0, false, "답 없음"));
        assertThat(QuestionService.gradeShort(3, null, List.of("8430")).reason()).isEqualTo("답 없음");
    }

    @Test
    void 객관식_SINGLE_정답_오답_답없음() {
        Set<Long> correct = Set.of(2L);
        assertThat(QuestionService.gradeChoice(5, Set.of(2L), correct, "B", "B")).isEqualTo(new QuestionService.Graded(5, true, "정답"));
        assertThat(QuestionService.gradeChoice(5, Set.of(1L), correct, "A", "B"))
                .isEqualTo(new QuestionService.Graded(0, false, "오답 (답: A / 정답: B)"));
        assertThat(QuestionService.gradeChoice(5, Set.of(), correct, "", "B")).isEqualTo(new QuestionService.Graded(0, false, "답 없음"));
    }

    @Test
    void 객관식_MULTI는_정확히_일치해야_정답이다() {
        Set<Long> correct = Set.of(1L, 3L);
        assertThat(QuestionService.gradeChoice(5, Set.of(3L, 1L), correct, "A, C", "A, C").score()).isEqualTo(5);
        assertThat(QuestionService.gradeChoice(5, Set.of(1L), correct, "A", "A, C").score()).isZero(); // 부분 점수 없음
        assertThat(QuestionService.gradeChoice(5, Set.of(1L, 2L, 3L), correct, "A, B, C", "A, C").score()).isZero();
        assertThat(QuestionService.gradeChoice(5, Set.of(2L), correct, "B", "A, C").correct()).isFalse();
    }
}
