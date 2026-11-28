package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.taskgen.dto.AltSolutionDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 특성 테스트: 저장되는 GenReport(report.json)의 내용·키 순서가 리팩터링 전후로 같은지 본다.
 * 시간이 섞이는 값(seconds, *Seconds, waitedMs)은 지우고 비교한다. 골든 파일이 없으면 이번 결과로 만든다.
 * 일부러 다시 만들려면 -Dgolden.update=true.
 */
class TaskGenReportGoldenTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final TaskGenRequest FORM = TaskGenFixtures.form();
    private static final String BAD = "원인을 찾아 고치세요.";
    private static final String OK = "금액을 확인하세요.";
    private static final String LEAK_MARK = "[숨은 결함(출제자만 아는 정보)]";
    private static final ReviewResultDto CLEAN = new ReviewResultDto(List.of());
    private static final AltSolutionDto ALT = new AltSolutionDto(List.of(new DevDraftDto.FileDraft("src/shop/Cart.java", "package shop;\nclass Cart {}\n")));
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/taskgen/golden");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 공통 도우미 ─────────────────────────────

    private static DevDraftDto draft(String line) {
        return TaskGenFixtures.edit(TaskGenFixtures.good(), b -> b.description(List.of("쿠폰 두 장을 쓰면 결제 금액이 이상합니다.", line)));
    }

    private static ReviewResultDto found(Violation... v) {
        return new ReviewResultDto(List.of(v));
    }

    private static Violation m5() {
        return new Violation("M5", BAD, "description", "원인 문장을 지운다");
    }

    private static TaskPipelineServiceTest.StageAi ai(ReviewResultDto... reviews) {
        var ai = new TaskPipelineServiceTest.StageAi().add(AltSolutionDto.class, ALT);
        for (var r : reviews) ai.add(ReviewResultDto.class, r);
        return ai;
    }

    private static TaskPipelineServiceTest.StageAi leakyAi() {
        return new TaskPipelineServiceTest.StageAi() {
            @Override
            public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
                if (type == ReviewResultDto.class && user.contains(LEAK_MARK)) {
                    order.add(type);
                    users.add(user);
                    efforts.add(effort);
                    @SuppressWarnings("unchecked")
                    Output<T> out = new Output<>((T) found(m5()), 1, 1);
                    return out;
                }
                return super.generate(model, system, user, type, maxTokens, effort);
            }
        }.add(ReviewResultDto.class, CLEAN).add(AltSolutionDto.class, ALT);
    }

    private static TaskGenProperties cap(double usd) {
        return new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, usd, null);
    }

    /** 시간이 섞이는 값을 지운다. 'because' 안의 Map.of 는 순서가 JVM 마다 달라 키를 정렬한다 */
    private static void scrub(JsonNode node, boolean sortKeys) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                if (n.equals("seconds") || n.endsWith("Seconds") || n.equals("modelSecondsByStage") || n.equals("waitedMs")) o.remove(n);
            }
            if (sortKeys) {
                List<String> rest = new ArrayList<>();
                o.fieldNames().forEachRemaining(rest::add);
                rest.sort(null);
                Map<String, JsonNode> copy = new java.util.LinkedHashMap<>();
                rest.forEach(k -> copy.put(k, o.get(k)));
                o.removeAll();
                copy.forEach(o::set);
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext();) {
                var e = it.next();
                scrub(e.getValue(), sortKeys || e.getKey().equals("because"));
            }
        } else if (node instanceof ArrayNode a) {
            a.forEach(n -> scrub(n, sortKeys));
        }
    }

    private static void assertGolden(String name, TaskGeneratorService.GenReport report) throws IOException {
        JsonNode tree = JSON.valueToTree(report);
        scrub(tree, false);
        String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree).replace("\r\n", "\n") + "\n";
        Path file = GOLDEN_DIR.resolve(name + ".json");
        if (!Files.exists(file) || Boolean.getBoolean("golden.update")) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(actual).isEqualTo(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    // ── 옛 방식(oneshot) ─────────────────────────────

    /** pipeline 이 없는 생성기: 한 번에 과제 전체를 받는다. 설명에 execFailText 가 있으면 실행 검사 실패 */
    static class OneShotHarness extends TaskGeneratorService<DevDraftDto> {
        GenReport report;
        final String execFailText;

        OneShotHarness(TaskPipelineServiceTest.StageAi ai, String execFailText) {
            super(ai, PROPS);
            this.execFailText = execFailText;
        }

        @Override
        protected String system(TaskGenRequest form) {
            return "";
        }

        @Override
        protected String user(TaskGenRequest form, List<GenCheckResultDto> failed) {
            return "";
        }

        @Override
        protected Class<DevDraftDto> draftType() {
            return DevDraftDto.class;
        }

        @Override
        protected List<GenCheckService<DevDraftDto>> checks(TaskGenRequest form, DevDraftDto d) {
            return List.of(new GenCheckService<>() {
                @Override
                public String name() {
                    return "시험 실행 검사";
                }

                @Override
                public GenCheckResultDto run(DevDraftDto x) {
                    boolean bad = execFailText != null && String.join("\n", x.description()).contains(execFailText);
                    return new GenCheckResultDto(name(), !bad, bad ? "실행 실패: " + execFailText : "통과");
                }
            });
        }

        @Override
        protected List<String> draftCompetencies(DevDraftDto d) {
            return List.of();
        }

        @Override
        protected String save(TaskGenRequest form, DevDraftDto d, GenReport report, long adminId) {
            this.report = report;
            return "saved";
        }
    }

    // ── 경우들 ─────────────────────────────

    @Test
    @DisplayName("단계별: 한 번에 통과")
    void stagedPass() throws IOException {
        var h = new TaskReviewLoopTest.Harness(ai(CLEAN), new TaskReviewLoopTest.FakeRun(List.of(draft(OK))), null);
        h.generate(FORM, 1, s -> {});
        assertGolden("staged-pass", h.report);
    }

    @Test
    @DisplayName("단계별: 답 노출 관문 실패 → 다시 만들기 1번 → 통과")
    void stagedGateFailThenPass() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD), draft(OK)));
        var h = new TaskReviewLoopTest.Harness(ai(found(m5()), CLEAN), run, null);
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).hasSize(1);
        assertGolden("staged-gate-fail-then-pass", h.report);
    }

    @Test
    @DisplayName("단계별: 계속 실패해 단계 2번 + 설계도부터 1번 쓰고 실패로 저장")
    void stagedBudgetExhausted() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD)));
        var h = new TaskReviewLoopTest.Harness(leakyAi(), run, null);
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).hasSize(3);
        assertGolden("staged-budget-exhausted", h.report);
    }

    @Test
    @DisplayName("단계별: 글 문제 2번 + 테스트 문제 2번(종류별 예산)")
    void stagedBudgetPerKind() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD), draft(BAD), draft("EXEC"), draft("EXEC"), draft(OK)));
        var h = new TaskReviewLoopTest.Harness(ai(found(m5()), found(m5()), CLEAN), run, "EXEC");
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).hasSize(4);
        assertGolden("staged-budget-per-kind", h.report);
    }

    @Test
    @DisplayName("단계별: 비용 상한에 걸려 다시 만들지 않고 멈춤")
    void stagedStoppedByCostCap() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD)));
        var h = new TaskReviewLoopTest.Harness(leakyAi(), run, null, cap(0.00001));
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).isEmpty();
        assertGolden("staged-cost-cap-stop", h.report);
    }

    @Test
    @DisplayName("단계별: 다시 돌 단계(ANSWER) 비용만으로 어림해 상한 안에서 다시 만듦")
    void stagedEstimateByPlannedStages() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD), draft(OK))) {
            @Override
            public List<StageUsageDto> usage() {
                return List.of(new StageUsageDto("BLUEPRINT", "gpt-6.1-terra", "medium", 100_000, 60_000, 1),
                        new StageUsageDto("ANSWER", "gpt-6.1-terra", "medium", 2_000, 2_000, 1));
            }

            @Override
            public List<String> plannedStages(List<GenCheckResultDto> failed) {
                return List.of("ANSWER");
            }
        };
        var h = new TaskReviewLoopTest.Harness(ai(found(m5()), CLEAN), run, null, cap(0.6));
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).hasSize(1);
        assertGolden("staged-estimate-planned-stages", h.report);
    }

    @Test
    @DisplayName("단계별: 설계도 문제는 곧바로 설계도부터 다시")
    void stagedBlueprintRestart() throws IOException {
        var run = new TaskReviewLoopTest.FakeRun(List.of(draft(BAD)));
        run.restarts = true;
        var h = new TaskReviewLoopTest.Harness(leakyAi(), run, null);
        h.generate(FORM, 1, s -> {});
        assertThat(run.redos).hasSize(1);
        assertGolden("staged-blueprint-restart", h.report);
    }

    @Test
    @DisplayName("옛 방식(oneshot): 한 번에 통과")
    void oneshotPass() throws IOException {
        var h = new OneShotHarness(new TaskPipelineServiceTest.StageAi().add(DevDraftDto.class, draft(OK)), null);
        h.generate(FORM, 1, s -> {});
        assertGolden("oneshot-pass", h.report);
    }

    @Test
    @DisplayName("옛 방식(oneshot): 실행 검사 실패 → 다시 생성 1번 → 통과")
    void oneshotRegenerateOnce() throws IOException {
        var ai = new TaskPipelineServiceTest.StageAi().add(DevDraftDto.class, draft("EXEC")).add(DevDraftDto.class, draft(OK));
        var h = new OneShotHarness(ai, "EXEC");
        var o = h.generate(FORM, 1, s -> {});
        assertThat(o.attempts()).isEqualTo(2);
        assertGolden("oneshot-regenerate-once", h.report);
    }
}
