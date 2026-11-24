package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import static com.pair.domain.taskgen.service.TaskGenFixtures.f;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.PairProperties;
import com.pair.infra.sandbox.LocalJvmRunner;

/**
 * 사람 검수에서 자동 확인이 놓친 `daily-usage`(서머타임 버그 개발 과제)와 같은 모양의 회귀 사례.
 * 실제 LocalJvmRunner 로 돌려서, 새 검사 1(핵심어 노출)·2(그럴듯한 오답)·4(장애 자료)가 실제로 걸리고
 * 3(분량)은 경고만 하는지, 고친 Draft 는 fail 검사를 모두 통과하는지 확인한다. AI 호출은 없다.
 */
@Tag("slow")
class DailyUsageRegressionTest {

    private static LocalJvmRunner runner;

    @BeforeAll
    static void setUp() {
        runner = new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
    }

    private static final TaskGenRequest FORM = new TaskGenRequest("DEV", "일별 이용량 집계 버그", "하루를 항상 24시간으로 계산", "SELF", 40);

    // ── 시작 코드(버그가 있음): 하루 끝을 시작 + 24시간으로 계산한다 ─────────────────

    private static final String EVENT = "package shop;\nimport java.time.Instant;\npublic record UsageEvent(Instant at, int amount) {}\n";

    private static String window(String body) {
        return "package shop;\nimport java.time.Duration;\nimport java.time.Instant;\nimport java.time.LocalDate;\nimport java.time.ZoneId;\nimport java.time.ZonedDateTime;\n"
                + "public record DailyWindow(Instant startInclusive, Instant endExclusive) {\n"
                + "    public static DailyWindow forDate(LocalDate date, ZoneId zone) {\n" + body
                + "        return new DailyWindow(start, end);\n    }\n}\n";
    }

    private static final String BUGGY = window("        Instant start = date.atStartOfDay(zone).toInstant();\n        Instant end = start.plus(Duration.ofDays(1));\n");
    private static final String SOLVED = window("        Instant start = date.atStartOfDay(zone).toInstant();\n        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();\n");
    /** 그럴듯하지만 틀린 수정 1: 겹치는 자정에서 늦은 쪽 시각을 고른다 (숨은 테스트 3개는 통과해 버린다) */
    private static final String WRONG_LATER_OFFSET = window("        Instant start = date.atStartOfDay(zone).withLaterOffsetAtOverlap().toInstant();\n"
            + "        Instant end = date.plusDays(1).atStartOfDay(zone).withLaterOffsetAtOverlap().toInstant();\n");
    /** 그럴듯하지만 틀린 수정 2: ZonedDateTime.plusDays(1) (날짜가 통째로 없는 날에서 틀린다) */
    private static final String WRONG_PLUS_DAYS = window("        ZonedDateTime first = date.atStartOfDay(zone);\n        Instant start = first.toInstant();\n"
            + "        Instant end = first.plusDays(1).toInstant();\n");

    private static final String AGGREGATOR = "package shop;\nimport java.time.LocalDate;\nimport java.time.ZoneId;\nimport java.util.List;\n"
            + "public class DailyUsageAggregator {\n    public int total(LocalDate date, ZoneId zone, List<UsageEvent> events) {\n"
            + "        DailyWindow w = DailyWindow.forDate(date, zone);\n        int sum = 0;\n        for (UsageEvent e : events) {\n"
            + "            if (!e.at().isBefore(w.startInclusive()) && e.at().isBefore(w.endExclusive())) sum += e.amount();\n        }\n        return sum;\n    }\n}\n";

    private static final String JUNIT = "import static org.junit.jupiter.api.Assertions.assertEquals;\nimport java.time.Instant;\nimport java.time.LocalDate;\n"
            + "import java.time.ZoneId;\nimport java.util.List;\nimport org.junit.jupiter.api.Test;\n";

    private static final String PUBLIC_TEST = "package shop;\n" + JUNIT + "class DailyUsageTest {\n    @Test\n    void aggregatesOrdinaryDayUsingHalfOpenBounds() {\n"
            + "        ZoneId zone = ZoneId.of(\"America/New_York\");\n        LocalDate date = LocalDate.of(2024, 3, 5);\n"
            + "        Instant start = date.atStartOfDay(zone).toInstant();\n        var events = List.of(new UsageEvent(start.minusSeconds(1), 7), new UsageEvent(start, 2),\n"
            + "                new UsageEvent(start.plusSeconds(86399), 3), new UsageEvent(start.plusSeconds(86400), 11));\n"
            + "        assertEquals(5, new DailyUsageAggregator().total(date, zone, events));\n    }\n}\n";

    private static final String HIDDEN_BASE = "package shop;\n" + JUNIT + "class HiddenDailyWindowTest {\n"
            + "    @Test\n    void springForwardDayHasFewerHours() {\n        var w = DailyWindow.forDate(LocalDate.of(2024, 3, 10), ZoneId.of(\"America/New_York\"));\n"
            + "        assertEquals(Instant.parse(\"2024-03-10T05:00:00Z\"), w.startInclusive());\n        assertEquals(Instant.parse(\"2024-03-11T04:00:00Z\"), w.endExclusive());\n    }\n"
            + "    @Test\n    void fallBackDayHasMoreHours() {\n        var w = DailyWindow.forDate(LocalDate.of(2024, 11, 3), ZoneId.of(\"America/New_York\"));\n"
            + "        assertEquals(Instant.parse(\"2024-11-03T04:00:00Z\"), w.startInclusive());\n        assertEquals(Instant.parse(\"2024-11-04T05:00:00Z\"), w.endExclusive());\n    }\n"
            + "    @Test\n    void dateThatDoesNotExistIsEmpty() {\n        var w = DailyWindow.forDate(LocalDate.of(2011, 12, 30), ZoneId.of(\"Pacific/Apia\"));\n"
            + "        assertEquals(w.startInclusive(), w.endExclusive());\n    }\n";

    /** 리뷰가 제안한 숨은 테스트 1개: 자정이 두 번 있는 날은 이른 쪽에서 시작해야 한다 */
    private static final String HIDDEN_OVERLAP = "    @Test\n    void repeatedMidnightStartsAtEarliestOffset() {\n"
            + "        var w = DailyWindow.forDate(LocalDate.of(2023, 11, 5), ZoneId.of(\"America/Havana\"));\n"
            + "        assertEquals(Instant.parse(\"2023-11-05T04:00:00Z\"), w.startInclusive());\n        assertEquals(Instant.parse(\"2023-11-06T05:00:00Z\"), w.endExclusive());\n    }\n";

    private static List<DevDraftDto.FileDraft> wrongFiles(String windowSource) {
        return List.of(f("src/shop/DailyWindow.java", windowSource));
    }

    private static DevDraftDto.FileDraft src(String name, String content) {
        return f("src/shop/" + name + ".java", content);
    }

    /** 검수에서 나온 daily-usage 와 같은 Draft: 설명에 원인 노출, 재현 자료 없음, 오답 하나가 숨은 테스트를 통과, 정답 1줄 */
    private static DevDraftDto reviewed() {
        return new DevDraftDto("일별 이용량 집계 오류", "전환이 있는 날의 일별 이용량이 어긋납니다.", "daily-usage", "장애 분석",
                List.of("날짜와 시간대에 따른 집계 구간을 점검하고, 서머타임 전환일에도 해당 현지 날짜에 속하는 기록만 합산하도록 수정해 주세요."),
                List.of("다음 현지 날짜의 시작 시각을 구간의 끝으로 사용합니다.", "그 날짜가 없는 날은 빈 구간으로 봅니다.", "서머타임 시작일과 종료일을 포함해 모든 날짜에 같은 규칙을 적용합니다."),
                "# 정답\n하루 끝은 시작 + 24시간이 아니라 다음 날짜의 시작을 시간대로 구한다.",
                List.of(src("UsageEvent", EVENT), src("DailyWindow", BUGGY), src("DailyUsageAggregator", AGGREGATOR),
                        f("test/shop/DailyUsageTest.java", PUBLIC_TEST)),
                List.of(f("test/shop/HiddenDailyWindowTest.java", HIDDEN_BASE + "}\n")),
                List.of(src("DailyWindow", SOLVED)),
                "보통", null,
                List.of("서머타임", "전환일", "24시간"),
                List.of(new DevDraftDto.WrongFix("겹치는 자정에서 늦은 쪽 시각을 골라 가장 이른 시각이 아니게 된다", wrongFiles(WRONG_LATER_OFFSET)),
                        new DevDraftDto.WrongFix("ZonedDateTime.plusDays(1) 은 날짜가 통째로 없는 날에서 빈 구간이 되지 않는다", wrongFiles(WRONG_PLUS_DAYS))),
                "AI 가 낸 수정이 특이한 시간대 규칙에서도 맞는지 어떻게 확인했나요?");
    }

    /** 검수 보고서의 고칠 것 1·2·3·4번을 반영한 Draft */
    private static DevDraftDto fixed() {
        String notes = "# 장애 접수 #2024-0311\n- 고객 시간대: America/New_York\n- 2024-03-10 일별 이용량이 2024-03-11 첫 시간 기록까지 포함해 크게 나옴\n"
                + "- 2024-11-03 일별 이용량에서 그날 마지막 1시간 기록이 빠짐\n- 다른 날짜는 정상\n- 같은 고객이 태평양 섬나라 시간대로도 조회했는데 어느 날짜는 합계가 0 이 아니라 이상한 값으로 나옴\n"
                + "- 접수 채널은 고객센터 전화이고 같은 날짜로 다시 조회해도 같은 결과가 반복됨\n- 고객은 달력의 모든 날짜가 존재한다고 알고 있어 해당 날짜의 이용량을 따로 문의함\n";
        return TaskGenFixtures.edit(reviewed(), b -> b
                .description(List.of("어느 날짜를 조회하든 해당 현지 날짜에 속하는 기록만 합산하도록 수정해 주세요."))
                .requirements(List.of("다음 현지 날짜의 시작 시각을 구간의 끝으로 사용합니다.", "그 날짜가 없는 날은 빈 구간으로 봅니다.", "모든 날짜와 시간대에 같은 규칙을 적용합니다."))
                .filesWith(f("NOTES.md", notes))
                .hiddenTests(List.of(f("test/shop/HiddenDailyWindowTest.java", HIDDEN_BASE + HIDDEN_OVERLAP + "}\n"))));
    }

    private List<GenCheckResultDto> run(DevDraftDto d) {
        return DevCheckService.forDraft(runner, d, FORM).stream().map(c -> c.run(d)).toList();
    }

    private static GenCheckResultDto named(List<GenCheckResultDto> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("daily-usage 와 같은 Draft: 1(핵심어 노출)·2(오답이 숨은 테스트 통과)·4(장애 자료 없음)가 fail 로 걸리고 3(분량)은 warn, 나머지는 통과")
    void reviewedCaseIsCaught() {
        var results = run(reviewed());

        // 기존 검사는 모두 통과했었다(리뷰 보고서와 같음): 시작 코드 통과, 숨은 테스트가 버그를 잡음, 정답 통과
        for (String name : List.of(DevCheckService.FORMAT, DevCheckService.NO_HINT, DevCheckService.START_PASSES, DevCheckService.HIDDEN_CATCHES,
                DevCheckService.SOLVED_PASSES, DevCheckService.TIME, DevCheckService.FOLLOW_UP)) {
            assertThat(named(results, name).ok()).as(name + ": " + named(results, name).detail()).isTrue();
        }

        var leak = named(results, DevCheckService.TRAP_WORDS);
        assertThat(leak.ok()).isFalse();
        assertThat(leak.level()).isEqualTo("fail");
        assertThat(leak.detail()).contains("설명").contains("서머타임").contains("요구사항");

        var wrong = named(results, DevCheckService.WRONG_FIXES);
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.level()).isEqualTo("fail");
        // 늦은 오프셋 오답(오답 1)만 통과했고, plusDays 오답(오답 2)은 Apia 날짜 없는 날에서 걸린다
        assertThat(wrong.detail()).contains("오답 1").contains("숨은 테스트를 모두 통과").doesNotContain("오답 2");

        var materials = named(results, DevCheckService.MATERIALS);
        assertThat(materials.ok()).isFalse();
        assertThat(materials.level()).isEqualTo("fail");

        var amount = named(results, DevCheckService.AMOUNT);
        assertThat(amount.ok()).isFalse();
        assertThat(amount.level()).isEqualTo("warn");
        assertThat(amount.detail()).contains("정답 변경 2줄, 40분 과제치고 적음");

        assertThat(results.stream().filter(GenCheckResultDto::needsRegeneration).map(GenCheckResultDto::name))
                .containsExactly(DevCheckService.TRAP_WORDS, DevCheckService.WRONG_FIXES, DevCheckService.MATERIALS);
    }

    @Test
    @DisplayName("보고서의 고칠 것(원인 단어 제거, 접수 메모, 겹치는 자정 숨은 테스트)을 반영하면 fail 검사를 모두 통과하고 분량 warn 만 남는다")
    void fixedCasePasses() {
        var results = run(fixed());
        assertThat(results.stream().filter(GenCheckResultDto::needsRegeneration).map(r -> r.name() + ": " + r.detail())).isEmpty();
        assertThat(results.stream().filter(r -> !r.ok()).map(GenCheckResultDto::name))
                .containsExactly(DevCheckService.AMOUNT, DevCheckService.ALT_SOLUTIONS); // 이 옛 Draft 에는 다른 풀이가 없어 warn
        assertThat(named(results, DevCheckService.WRONG_FIXES).detail()).contains("오답 2개 모두");
    }
}
