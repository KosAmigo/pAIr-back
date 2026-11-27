package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.AltSolutionDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관문 검사(답 노출 이중·정답 키·테스트 근거·다른 풀이 실행), 실패 라우팅, 다시 만들기 예산, 관문 전체 재검사, 경고, 값 비교, 기계적 수정,
 * 문항 글 단계의 입력을 AI 없이 확인한다.
 * DEV 한 라운드의 모델 호출 순서: 답 노출(Sonnet), 답 노출(terra), 테스트 근거, 다른 풀이 작성(실행은 코드), 마지막에 일관성 경고
 */
class TaskReviewLoopTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final TaskGenRequest FORM = TaskGenFixtures.form();
    private static final String BAD = "원인을 찾아 고치세요.";
    private static final String OK = "금액을 확인하세요.";

    // ── 가짜 파이프라인과 시험용 생성기 ─────────────────────────────

    static class FakeRun implements TaskPipelineService.Run<DevDraftDto> {
        final List<DevDraftDto> drafts;
        int next = 1;
        final List<List<GenCheckResultDto>> redos = new ArrayList<>();
        boolean restarts;

        FakeRun(List<DevDraftDto> drafts) {
            this.drafts = drafts;
        }

        @Override
        public DevDraftDto create(Consumer<String> onStep) {
            return drafts.get(0);
        }

        @Override
        public DevDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            redos.add(failed);
            return drafts.get(Math.min(next++, drafts.size() - 1));
        }

        @Override
        public boolean restartsFrom(List<GenCheckResultDto> failed) {
            return restarts;
        }

        @Override
        public BlueprintDto blueprint() {
            return TaskPipelineServiceTest.devBlueprint();
        }

        @Override
        public List<GenCheckResultDto> checks() {
            return List.of();
        }

        @Override
        public List<StageUsageDto> usage() {
            return List.of(new StageUsageDto("BLUEPRINT", "gpt-6.1-terra", "medium", 10, 20, 1));
        }

        @Override
        public List<String> redone() {
            return List.of("ANSWER");
        }
    }

    /** 실행 검사: 설명에 execFailText 가 있으면 실패. 점검 입력은 실제 입력 만들기를 쓴다 */
    static class Harness extends TaskGeneratorService<com.pair.domain.taskgen.dto.DevDraftDto> {
        GenReport report;
        DevDraftDto saved;
        final String execFailText;
        java.util.function.Function<List<DevDraftDto.FileDraft>, ReviewExtra> alt = files -> new ReviewExtra(List.of(), Map.of());

        Harness(TaskPipelineServiceTest.StageAi ai, FakeRun run, String execFailText) {
            this(ai, run, execFailText, PROPS);
        }

        Harness(TaskPipelineServiceTest.StageAi ai, FakeRun run, String execFailText, TaskGenProperties props) {
            super(ai, props, new TaskPipelineService<DevDraftDto>() {
                @Override
                public Run<DevDraftDto> start(TaskGenRequest form) {
                    return run;
                }
            });
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
        protected List<GenCheckService<DevDraftDto>> checks(TaskGenRequest form, DevDraftDto draft) {
            return List.of(new GenCheckService<>() {
                @Override
                public String name() {
                    return "시험 실행 검사";
                }

                @Override
                public GenCheckResultDto run(DevDraftDto d) {
                    boolean bad = execFailText != null && String.join("\n", d.description()).contains(execFailText);
                    return new GenCheckResultDto(name(), !bad, bad ? "실행 실패: " + execFailText : "통과");
                }
            });
        }

        @Override
        protected List<String> draftCompetencies(DevDraftDto draft) {
            return List.of();
        }

        @Override
        protected String save(TaskGenRequest form, DevDraftDto draft, GenReport report, long adminId) {
            this.report = report;
            this.saved = draft;
            return "saved";
        }

        @Override
        protected boolean devTrack() {
            return true;
        }

        @Override
        protected boolean reviewable() {
            return true;
        }

        @Override
        protected String checkInput(TaskReviewService.Check check, TaskGenRequest form, BlueprintDto bp, DevDraftDto draft) {
            return TaskReviewService.devInput(check, form, bp, draft);
        }

        @Override
        protected ReviewExtra altRun(DevDraftDto draft, List<DevDraftDto.FileDraft> files) {
            return alt.apply(files);
        }
    }

    private static DevDraftDto draft(String descriptionLine) {
        return TaskGenFixtures.edit(TaskGenFixtures.good(), b -> b.description(List.of("쿠폰 두 장을 쓰면 결제 금액이 이상합니다.", descriptionLine)));
    }

    private static ReviewResultDto found(Violation... v) {
        return new ReviewResultDto(List.of(v));
    }

    private static final ReviewResultDto CLEAN = found();
    private static final AltSolutionDto ALT = new AltSolutionDto(List.of(new DevDraftDto.FileDraft("src/shop/Cart.java", "package shop;\nclass Cart {}\n")));
    private static final String LEAK_MARK = "[숨은 결함(출제자만 아는 정보)]";

    private static Violation m5() {
        return new Violation("M5", BAD, "description", "원인 문장을 지운다");
    }

    /** 점검 응답: 순서대로 쓰고 마지막 값을 계속 쓴다 */
    private static TaskPipelineServiceTest.StageAi ai(ReviewResultDto... reviews) {
        var ai = new TaskPipelineServiceTest.StageAi().add(AltSolutionDto.class, ALT);
        for (var r : reviews) ai.add(ReviewResultDto.class, r);
        return ai;
    }

    /** 답 노출 점검(입력에 숨은 결함이 든 것)만 항상 위반을 내고, 나머지는 깨끗한 AI */
    private static TaskPipelineServiceTest.StageAi leakyAi(boolean onlySecond) {
        return new TaskPipelineServiceTest.StageAi() {
            int leakCalls;

            @Override
            public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
                if (type == ReviewResultDto.class && user.contains(LEAK_MARK)) {
                    order.add(type);
                    users.add(user);
                    efforts.add(effort);
                    boolean flag = !onlySecond || leakCalls++ % 2 == 1; // onlySecond: 두 번째 눈(terra)만 걸림
                    @SuppressWarnings("unchecked")
                    Output<T> out = new Output<>((T) (flag ? found(m5()) : CLEAN), 1, 1);
                    return out;
                }
                return super.generate(model, system, user, type, maxTokens, effort);
            }
        }.add(ReviewResultDto.class, CLEAN).add(AltSolutionDto.class, ALT);
    }

    private static long calls(TaskPipelineServiceTest.StageAi ai, Class<?> type) {
        return ai.order.stream().filter(t -> t == type).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gatesReport(Harness h) {
        return (Map<String, Object>) h.report.details().get("gates");
    }

    // ── 관문 구성 ─────────────────────────────

    @Test
    @DisplayName("한 라운드: DEV 관문은 답 노출 2번(Sonnet, terra)·테스트 근거·다른 풀이이고 일관성은 마지막에 한 번(경고). 단계별 모델·토큰이 report 에 남고 최상위 model 에 쓴 모델이 모두 적힌다")
    void devRoundRunsGates() {
        var ai = ai(CLEAN);
        var h = new Harness(ai, new FakeRun(List.of(draft(OK))), null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        assertThat(ai.order).containsExactly(ReviewResultDto.class, ReviewResultDto.class, ReviewResultDto.class, AltSolutionDto.class, ReviewResultDto.class);
        @SuppressWarnings("unchecked")
        var stages = (List<StageUsageDto>) h.report.details().get("stages");
        assertThat(stages).extracting(StageUsageDto::stage).containsExactly("BLUEPRINT", "REVIEW:LEAK", "REVIEW2:LEAK", "REVIEW:BACKING", "REVIEW:ALT", "REVIEW:CONSISTENCY");
        assertThat(stages).filteredOn(u -> u.stage().equals("REVIEW2:LEAK")).extracting(StageUsageDto::model).containsExactly("claude-sonnet-5-5");
        assertThat(stages).filteredOn(u -> u.stage().startsWith("REVIEW:")).extracting(StageUsageDto::model).containsOnly("gpt-6.1-sol");
        assertThat(ai.efforts).containsOnly("medium");
        assertThat(h.report.model()).isEqualTo("gpt-6.1-terra, gpt-6.1-sol, claude-sonnet-5-5");
        assertThat(h.report.checks()).extracting(GenCheckResultDto::name).contains(TaskPipelineService.LEAK_A_GATE, TaskPipelineService.LEAK_B_GATE,
                TaskPipelineService.BACKING_GATE, TaskPipelineService.ALT_GATE);
        assertThat(gatesReport(h)).containsEntry("passed", true);
    }

    @Test
    @DisplayName("답 노출은 Sonnet 과 terra 가 따로 본다: 둘 중 하나만 걸어도 실패하고, 실패는 설명 쪽 단계(작성)를 가리킨다")
    void eitherLeakCheckFails() {
        var ai = leakyAi(true); // 1차(Sonnet) 깨끗, 2차(terra)만 걸림
        var run = new FakeRun(List.of(draft(BAD), draft(OK)));
        var h = new Harness(ai, run, null);
        h.generate(FORM, 1, s -> {});

        assertThat(run.redos).isNotEmpty();
        var failed = run.redos.get(0);
        assertThat(failed).extracting(GenCheckResultDto::name).containsExactly(TaskPipelineService.LEAK_B_GATE);
        assertThat(DevPipelineService.owners(failed.get(0), List.of())).containsExactly(Stage.WRITING);
        assertThat(failed.get(0).detail()).contains("M5").contains("description");
    }

    @Test
    @DisplayName("실패 라우팅: 답 노출은 위치로(설명·자료 → 작성, 문항·보기 → 문항 글, 시작 코드·파일 → 시작 코드), 정답 키·테스트 근거·다른 풀이는 정답 쪽")
    void gateFailureRouting() {
        GenCheckResultDto leakDesc = new GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [description] 원인을 찾아 → 지운다");
        GenCheckResultDto leakQuestion = new GenCheckResultDto(TaskPipelineService.LEAK_B_GATE, false, "M5 [question:2] 계산 방법을 정함 → 지운다");
        GenCheckResultDto leakChoice = new GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [choice:1:2] 다른 문항 정답 → 바꾼다");
        GenCheckResultDto leakCode = new GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [src/shop/Cart.java] 주석이 원인을 말함 → 지운다");
        GenCheckResultDto leakDoc = new GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [문서 자료/안내.pdf 1쪽] 규칙 문장 → 지운다");

        assertThat(BizPipelineService.owners(leakDesc)).containsExactly(Stage.WRITING);
        assertThat(BizPipelineService.owners(leakQuestion)).containsExactly(Stage.QUESTIONS);
        assertThat(BizPipelineService.owners(leakChoice)).containsExactly(Stage.QUESTIONS);
        assertThat(BizPipelineService.owners(leakDoc)).containsExactly(Stage.WRITING);
        assertThat(DevPipelineService.owners(leakDesc, List.of())).containsExactly(Stage.WRITING);
        assertThat(DevPipelineService.owners(leakCode, List.of())).containsExactly(Stage.CODE);

        // 관문이 단계를 적지 않았을 때의 기본: 정답 키는 정답 쪽, 테스트 근거는 작성(자료 보강), 다른 풀이는 설계도(사례가 너무 엄격)
        var key = new GenCheckResultDto(TaskPipelineService.KEY_GATE, false, "M2 [question:1] 정답이 둘");
        assertThat(BizPipelineService.owners(key)).containsExactly(Stage.ANSWER);
        var backing = new GenCheckResultDto(TaskPipelineService.BACKING_GATE, false, "M2 [hiddenTest:H x] 단정 → 예를 더한다");
        assertThat(DevPipelineService.owners(backing, List.of())).containsExactly(Stage.WRITING);
        var alt = new GenCheckResultDto(TaskPipelineService.ALT_GATE, false, "M2 [hiddenTest:x] 실패 → 완화");
        assertThat(DevPipelineService.owners(alt, List.of())).containsExactly(Stage.BLUEPRINT);
        // 계획: 문항 글만 다시 돌면 정답 쪽은 그대로, 작성이 다시 돌면 BIZ 는 정답 쪽도 다시
        var onlyQuestions = StageRouteService.plan(List.of(leakQuestion), BizPipelineService::owners, java.util.EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER),
                java.util.EnumSet.of(Stage.WRITING));
        assertThat(onlyQuestions.stages()).containsExactly(Stage.QUESTIONS);
        var writing = StageRouteService.plan(List.of(leakDesc), BizPipelineService::owners, java.util.EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER),
                java.util.EnumSet.of(Stage.WRITING));
        assertThat(writing.stages()).containsExactlyInAnyOrder(Stage.WRITING, Stage.ANSWER);
    }

    // ── 다시 만들기 예산과 관문 전체 재검사 ─────────────────────────────

    @Test
    @DisplayName("예산: 단계 다시 생성 2번 + 설계도부터 1번, 그래도 실패하면 실패 이유와 함께 저장(passed=false). 이력에 단계와 이유가 남는다")
    void budgetIsTwoPlusOne() {
        var ai = leakyAi(false); // 답 노출이 항상 걸림
        var run = new FakeRun(List.of(draft(BAD)));
        var h = new Harness(ai, run, null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(run.redos).hasSize(3);
        assertThat(o.passed()).isFalse();
        assertThat(o.attempts()).isEqualTo(4);
        assertThat(h.report.checks()).anyMatch(c -> c.name().equals(TaskPipelineService.LEAK_A_GATE) && !c.ok());
        // 마지막 다시 만들기는 설계도부터: 실패 내용을 한 줄로 묶어 넘긴다
        assertThat(run.redos.get(2)).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo(TaskGeneratorService.RESTART_CHECK));
        assertThat(run.redos.get(0)).extracting(GenCheckResultDto::name).contains(TaskPipelineService.LEAK_A_GATE);
        @SuppressWarnings("unchecked")
        var regens = (List<Map<String, Object>>) gatesReport(h).get("regenerations");
        assertThat(regens).extracting(m -> m.get("kind")).containsExactly("stages", "stages", "blueprint");
        assertThat(regens.get(0)).containsKey("stages").containsKey("because");
        assertThat(gatesReport(h).get("budget")).isEqualTo(Map.of("textRegens", 2, "maxTextRegens", 2, "testRegens", 0, "maxTestRegens", 2, "stageRegens", 2, "restarts", 1, "maxRestarts", 1));
        // 답 노출이 먼저 걸리면 나머지 AI 관문은 그 라운드에 돌지 않는다: 시도마다 관문 호출은 1번뿐
        assertThat(calls(ai, ReviewResultDto.class)).isEqualTo(4);
        assertThat(calls(ai, AltSolutionDto.class)).isZero();
        @SuppressWarnings("unchecked")
        var rounds = (List<Map<String, Object>>) gatesReport(h).get("rounds");
        assertThat(rounds).allSatisfy(r -> assertThat((List<?>) r.get("gates")).hasSize(1));
    }

    @Test
    @DisplayName("다시 만들기 예산은 문제 종류별: 글 문제(답 노출) 2번을 다 써도 테스트·정답 문제(실행 검사)는 따로 2번 더 다시 만든다. report 에 종류별 사용량이 남는다")
    void budgetsPerKind() {
        var ai = ai(found(m5()), found(m5()), CLEAN); // 답 노출은 처음 두 라운드만 걸림
        var run = new FakeRun(List.of(draft(BAD), draft(BAD), draft("EXEC"), draft("EXEC"), draft(OK)));
        var h = new Harness(ai, run, "EXEC");
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        assertThat(run.redos).hasSize(4); // 글 2 + 테스트 2 (옛 방식의 합계 예산으로는 모자란다)
        assertThat(gatesReport(h).get("budget")).isEqualTo(Map.of("textRegens", 2, "maxTextRegens", 2, "testRegens", 2, "maxTestRegens", 2, "stageRegens", 4,
                "restarts", 0, "maxRestarts", 1));
        assertThat(run.redos.get(2)).extracting(GenCheckResultDto::name).containsExactly("시험 실행 검사");
    }

    @Test
    @DisplayName("문제 종류: 작성·문항 글 단계만 고치면 text, 정답 쪽·시작 코드가 걸리면 test, 한자 검사는 상세의 표시를 따라간다")
    void kindsOfFailures() {
        var leak = new GenCheckResultDto(TaskPipelineService.LEAK_A_GATE, false, "M5 [description] 원인 → 지운다");
        var key = new GenCheckResultDto(TaskPipelineService.KEY_GATE, false, "M2 [Q1] 정답 키");
        var han = TaskWritingService.hanCheck(new java.util.LinkedHashMap<>(java.util.Map.of("[작성]", "結論은 이렇다")) {{ put("[정답]", "結論은 이렇다"); }});
        var hanText = TaskWritingService.hanCheck(java.util.Map.of("[작성]", "結論은 이렇다"));
        var dup = new GenCheckResultDto(TaskPipelineService.DUPLICATE_CHECK, false, "같은 문장");

        assertThat(StageRouteService.kinds(List.of(leak, dup), BizPipelineService::owners)).containsExactly("text");
        assertThat(StageRouteService.kinds(List.of(leak, key), BizPipelineService::owners)).containsExactlyInAnyOrder("text", "test");
        assertThat(StageRouteService.kinds(List.of(han), BizPipelineService::owners)).containsExactly("test"); // 정답 쪽도 걸림
        assertThat(StageRouteService.kinds(List.of(hanText), BizPipelineService::owners)).containsExactly("text");
        assertThat(BizPipelineService.owners(han)).containsExactlyInAnyOrder(Stage.WRITING, Stage.ANSWER);
        assertThat(DevPipelineService.owners(new GenCheckResultDto(TaskPipelineService.ADDED_CALLED_CHECK, false, "x"), List.of())).containsExactly(Stage.ANSWER);
    }

    @Test
    @DisplayName("비용 상한: 다음 다시 만들기가 상한을 넘기면 다시 만들지 않고 '비용 상한' 실패로 저장한다. report 에 총비용·단계별 비용·시도 수가 남는다")
    void stopsAtCostCap() {
        var ai = leakyAi(false);
        var run = new FakeRun(List.of(draft(BAD)));
        var tiny = new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0.00001, null);
        var h = new Harness(ai, run, null, tiny);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(run.redos).isEmpty();
        assertThat(o.passed()).isFalse();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(h.report.checks()).anyMatch(c -> c.name().equals(TaskGeneratorService.COST_CAP_CHECK) && !c.ok() && c.detail().contains("상한"));
        @SuppressWarnings("unchecked")
        var cost = (Map<String, Object>) h.report.details().get("cost");
        assertThat(cost).containsEntry("stoppedByCap", true).containsEntry("attempts", 1).containsEntry("capUsd", 0.00001);
        assertThat((Double) cost.get("totalUsd")).isPositive();
        assertThat(cost.get("byStageUsd").toString()).contains("BLUEPRINT=").contains("REVIEW:LEAK=");
    }

    @Test
    @DisplayName("비용 상한 안이면 다시 만든다(기본 상한 $0.5): 총비용이 report 에 남고 상한을 넘지 않는다")
    void regeneratesUnderCostCap() {
        var ai = ai(found(m5()), CLEAN);
        var run = new FakeRun(List.of(draft(BAD), draft(OK)));
        var h = new Harness(ai, run, null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        assertThat(run.redos).hasSize(1);
        @SuppressWarnings("unchecked")
        var cost = (Map<String, Object>) h.report.details().get("cost");
        assertThat(cost).containsEntry("stoppedByCap", false).containsEntry("attempts", 2);
        assertThat((Double) cost.get("totalUsd")).isBetween(0.0, 0.5);
    }

    @Test
    @DisplayName("비용 상한: 다음 다시 만들기 비용은 다시 돌 단계(ANSWER)가 앞 시도에서 쓴 비용만으로 어림한다(설계도가 비싸도 정답 쪽만 다시 돌면 상한 안). 어림 근거가 report 에 남는다")
    void estimatesOnlyRegeneratedStages() {
        var ai = ai(found(m5()), CLEAN);
        var run = new FakeRun(List.of(draft(BAD), draft(OK))) {
            @Override
            public List<StageUsageDto> usage() {
                return List.of(new StageUsageDto("BLUEPRINT", "gpt-6.1-terra", "medium", 100_000, 60_000, 1), // 약 $0.4: 예전 어림(가장 비싼 호출)이면 상한 0.6 을 넘는다
                        new StageUsageDto("ANSWER", "gpt-6.1-terra", "medium", 2_000, 2_000, 1));
            }

            @Override
            public List<String> plannedStages(List<GenCheckResultDto> failed) {
                return List.of("ANSWER");
            }
        };
        var cap = new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0.6, null);
        var h = new Harness(ai, run, null, cap);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(run.redos).hasSize(1);
        assertThat(o.passed()).isTrue();
        @SuppressWarnings("unchecked")
        var cost = (Map<String, Object>) h.report.details().get("cost");
        assertThat(cost).containsEntry("stoppedByCap", false).containsEntry("capUsd", 0.6);
        assertThat(cost.get("estimates").toString()).contains("stages=[ANSWER]").contains("stageUsd={ANSWER=").contains("method=");
    }

    @Test
    @DisplayName("설계도 문제로 가리키는 실패는 단계 예산을 쓰지 않고 곧바로 설계도부터(1번)")
    void blueprintFailureRestartsDirectly() {
        var ai = leakyAi(false);
        var run = new FakeRun(List.of(draft(BAD)));
        run.restarts = true;
        var h = new Harness(ai, run, null);
        h.generate(FORM, 1, s -> {});

        assertThat(run.redos).hasSize(1); // 설계도부터 1번 뒤에도 실패하면 더 만들지 않는다
        assertThat(run.redos.get(0)).extracting(GenCheckResultDto::name).contains(TaskPipelineService.LEAK_A_GATE); // 실제 실패 내용이 그대로 넘어감
    }

    @Test
    @DisplayName("다시 만들 때마다 관문을 처음부터 다시 돈다: 1차는 답 노출 1차가 걸려 거기서 멈추고, 2차는 4개 모두 돌고, 통과하면 멈춘다")
    void fullGatesAfterEveryRegeneration() {
        var ai = ai(found(m5()), CLEAN); // 첫 호출(1차 Sonnet 답 노출)만 위반, 이후 깨끗
        var run = new FakeRun(List.of(draft(BAD), draft(OK)));
        var h = new Harness(ai, run, null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        assertThat(run.redos).hasSize(1);
        // 1차: 답 노출 2 + 근거 1 + 다른 풀이 1, 2차: 같은 4개, 마지막 일관성 1
        // 1차: 답 노출 1차가 걸리면 거기서 멈춤(관문 1개), 2차: 4개 + 일관성 1
        assertThat(calls(ai, ReviewResultDto.class)).isEqualTo(1 + 3 + 1);
        assertThat(calls(ai, AltSolutionDto.class)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var rounds = (List<Map<String, Object>>) gatesReport(h).get("rounds");
        assertThat(rounds).hasSize(2);
        assertThat((List<?>) rounds.get(0).get("gates")).hasSize(1);
        assertThat((List<?>) rounds.get(1).get("gates")).hasSize(4);
        assertThat(h.saved.description()).contains(OK);
    }

    @Test
    @DisplayName("코드 검사가 실패하면 AI 관문은 돌리지 않고(비용) 그 단계부터 다시, 기록에 '돌리지 않음'이 남는다")
    void noGatesWhenCodeChecksFail() {
        var ai = ai(CLEAN);
        var run = new FakeRun(List.of(draft(BAD), draft(OK)));
        var h = new Harness(ai, run, BAD);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(run.redos).hasSize(1);
        assertThat(run.redos.get(0)).extracting(GenCheckResultDto::name).containsExactly("시험 실행 검사");
        assertThat(o.passed()).isTrue();
        @SuppressWarnings("unchecked")
        var rounds = (List<Map<String, Object>>) gatesReport(h).get("rounds");
        assertThat(rounds.get(0).get("gates")).isEqualTo("코드 검사가 실패해 돌리지 않음");
        assertThat(rounds.get(0).get("codeChecksFailed")).isEqualTo(List.of("시험 실행 검사"));
    }

    // ── 경고, 범위, 다른 풀이 ─────────────────────────────

    @Test
    @DisplayName("일관성(C)·품질(Q)은 경고만: 관문을 통과하고 경고 검사로 남는다. 맡지 않은 항목(점검 밖의 M5, 문체 C6)은 버린다")
    void warningsNeverFail() {
        var ai = ai(CLEAN, CLEAN, found(new Violation("Q1", "채움 문장", "description", "줄인다"), new Violation("M5", "x", "description", "y")), // 근거 점검: Q 는 경고, 맡지 않은 M5 는 버림
                found(new Violation("C2", "숫자 불일치", "description", "맞춘다"), new Violation("C6", "문체", "description", "고친다")));
        var h = new Harness(ai, new FakeRun(List.of(draft(OK))), null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        var warns = h.report.checks().stream().filter(c -> c.name().equals(TaskGeneratorService.WARN_CHECK)).toList();
        assertThat(warns).hasSize(2).allMatch(c -> !c.ok() && !c.needsRegeneration());
        assertThat(warns).anyMatch(c -> c.detail().contains("Q1")).anyMatch(c -> c.detail().contains("C2"));
        assertThat(warns).noneMatch(c -> c.detail().contains("C6") || c.detail().contains("M5"));
        @SuppressWarnings("unchecked")
        var round = ((List<Map<String, Object>>) ((List<Map<String, Object>>) gatesReport(h).get("rounds")).get(0).get("gates"));
        assertThat(round).anyMatch(m -> m.containsKey("droppedOutOfScope"));
    }

    @Test
    @DisplayName("다른 풀이 실행: 검수자의 풀이가 숨은 테스트에서 떨어지면 정답 쪽 실패(M2), 통과하면 관문 통과. 실행한 풀이는 report 에 남는다")
    void alternativeSolutionRun() {
        var ai = ai(CLEAN);
        var run = new FakeRun(List.of(draft(OK), draft(OK)));
        var h = new Harness(ai, run, null);
        var failing = new int[] {0};
        h.alt = files -> failing[0]++ == 0
                ? new TaskGeneratorService.ReviewExtra(List.of(new Violation("M2", "다른 풀이가 테스트 1개에서 실패", "hiddenTest", "완화한다")), Map.of("altSolutionFailed", 1))
                : new TaskGeneratorService.ReviewExtra(List.of(), Map.of("altSolutionFailed", 0));
        var o = h.generate(FORM, 1, s -> {});

        assertThat(run.redos).hasSize(1);
        assertThat(run.redos.get(0)).extracting(GenCheckResultDto::name).containsExactly(TaskPipelineService.ALT_GATE);
        assertThat(DevPipelineService.owners(run.redos.get(0).get(0), List.of())).containsExactly(Stage.BLUEPRINT); // 사례가 너무 엄격: 설계도
        assertThat(o.passed()).isTrue();
    }

    @Test
    @DisplayName("관문 호출이 실패하면 과제를 버리지 않고 경고로 남긴다(통과 여부는 코드 검사가 정함)")
    void gateCallFailureIsAWarning() {
        var ai = new TaskPipelineServiceTest.StageAi() {
            @Override
            public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
                throw new IllegalStateException("호출 실패");
            }
        };
        var h = new Harness(ai, new FakeRun(List.of(draft(OK))), null);
        var o = h.generate(FORM, 1, s -> {});

        assertThat(o.passed()).isTrue();
        assertThat(h.report.checks()).anyMatch(c -> c.name().equals(TaskGeneratorService.GATE_SKIPPED) && !c.needsRegeneration());
        assertThat(h.report.retryError()).contains("관문 검사 호출 실패");
    }

    @Test
    @DisplayName("검사 안에서 부른 모델(BIZ 풀이 검사)의 토큰이 report 의 stages 에 SOLVER 로 남는다")
    void solverUsageIsRecorded() {
        var ai = ai(CLEAN);
        var h = new Harness(ai, new FakeRun(List.of(draft(OK))), null) {
            @Override
            protected List<GenCheckService<DevDraftDto>> checks(TaskGenRequest form, DevDraftDto draft) {
                recordUsage(new StageUsageDto("SOLVER", "gpt-6.1-terra", "medium", 321, 45, 2));
                return super.checks(form, draft);
            }
        };
        h.generate(FORM, 1, s -> {});

        @SuppressWarnings("unchecked")
        var stages = (List<StageUsageDto>) h.report.details().get("stages");
        assertThat(stages).anyMatch(u -> u.stage().equals("SOLVER") && u.inputTokens() == 321 && u.outputTokens() == 45);

        var gen = new BizTaskGeneratorService(null, PROPS, new PdfService(), (m, p) -> "답", null, null);
        var sink = new ArrayList<StageUsageDto>();
        assertThat(gen.countedSolver(sink::add).solve("gpt-6.1-terra", "문제")).isEqualTo("답");
        assertThat(sink).singleElement().satisfies(u -> assertThat(u.stage()).isEqualTo("SOLVER"));
    }

    // ── 점검 입력과 프롬프트 ─────────────────────────────

    @Test
    @DisplayName("점검마다 필요한 입력만: 답 노출은 결함+보이는 글, 테스트 근거는 사례+숨은 테스트, 일관성은 보이는 글+의도한 결함(경고용), 다른 풀이는 정답 풀이+사례")
    void checkInputsAreNarrow() {
        BlueprintDto bp = TaskPipelineServiceTest.devBlueprint();
        DevDraftDto d = TaskGenFixtures.good();
        String hiddenName = "couponOnce", solved = "sum += 11;", flaw = bp.flaws().get(0).what();

        String leak = TaskReviewService.devInput(TaskReviewService.Check.LEAK, FORM, bp, d);
        assertThat(leak).contains(flaw).contains("[설명]").contains("[보이는 파일 src/shop/Cart.java]").contains("[후속 질문]").doesNotContain(hiddenName).doesNotContain(solved);

        String backing = TaskReviewService.devInput(TaskReviewService.Check.BACKING, FORM, bp, d);
        assertThat(backing).contains("[사례별 근거와 테스트]").contains("사례 totalsExactly | 기대 결과:").doesNotContain(solved); // 사례별 묶음(테스트 본문은 사례 이름과 맞는 메서드만)

        String consistency = TaskReviewService.devInput(TaskReviewService.Check.CONSISTENCY, FORM, bp, d);
        assertThat(consistency).contains("[의도한 결함(모순으로 보지 않음)]").contains("[판단 지점(의도한 모호함)]").doesNotContain(hiddenName);

        String alt = TaskReviewService.devInput(TaskReviewService.Check.ALT, FORM, bp, d);
        assertThat(alt).contains("[공식 정답 풀이 src/shop/Cart.java]").contains(solved).doesNotContain(hiddenName);
        assertThat(TaskReviewService.devInput(TaskReviewService.Check.ANSWER_KEY, FORM, bp, d)).isNull(); // BIZ 점검

        BizDraftDto biz = BizFixtures.good("inputs");
        BlueprintDto bbp = TaskPipelineServiceTest.bizBlueprint();
        String key = TaskReviewService.bizInput(TaskReviewService.Check.ANSWER_KEY, FORM, bbp, biz);
        assertThat(key).contains("[판단 지점(설계도)]").contains("[설계도 사례(기대 결과)]").contains("(정답)").contains("[응시자 자료(문서·CSV)]");
        String bleak = TaskReviewService.bizInput(TaskReviewService.Check.LEAK, FORM, bbp, biz);
        assertThat(bleak).contains("[정답 키(출제자만 아는 정보)]").contains("[문서 ").contains("[CSV ").contains(bbp.conclusion().wrong());
        assertThat(TaskReviewService.bizInput(TaskReviewService.Check.BACKING, FORM, bbp, biz)).isNull(); // DEV 점검
        assertThat(TaskReviewService.bizInput(TaskReviewService.Check.CONSISTENCY, FORM, bbp, biz)).contains("[의도한 결함").doesNotContain("(정답)");
    }

    @Test
    @DisplayName("점검 프롬프트는 resources/taskgen/review/ 파일에서 읽고, 고치는 단계의 프롬프트는 없다")
    void promptsComeFromResources() {
        for (var c : TaskReviewService.Check.values()) assertThat(TaskPromptService.check(c.resource)).isNotBlank();
        assertThat(TaskPromptService.check("leak-dev")).contains("M5").contains("NOTES").contains("문항·보기").doesNotContain("M2").doesNotContain("C1");
        assertThat(TaskPromptService.check("backing-cases")).contains("기대 결과를 직접 정하는가").doesNotContain("M5");
        assertThat(TaskPromptService.check("backing")).contains("null").doesNotContain("M5");
        assertThat(TaskPromptService.check("answer-key")).contains("판단 지점을 객관식으로 물음").contains("계산 검증은 코드가 합니다");
        assertThat(TaskPromptService.check("consistency")).contains("의도한 결함").contains("경고용");
        assertThat(TaskPromptService.check("alt-solution")).contains("서버가 이 풀이를 숨은 테스트로 실제로 돌립니다");
        assertThat(TaskPromptService.blueprint(false, 20, "깊이")).contains("[Flaw types]").contains("R3 회사 반올림").doesNotContain("B4 부가세");
        assertThat(java.util.Arrays.stream(TaskPipelineService.Stage.values()).map(Enum::name)).doesNotContain("FIX");
    }

    // ── 문항 글(3단계): 결함을 모른다 ─────────────────────────────

    @Test
    @DisplayName("문항 글 단계 입력에는 결함·근거·기대 결과·함정 결론이 없고, 구할 값과 섞은 보기 후보만 있다")
    void questionStageKnowsNoFlaw() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        String in = BizQuestionService.input(b, "캠페인 예산을 검토해 1페이지 기획안을 쓰세요.", "");

        assertThat(in).contains("배정 후보 결론").contains("보기 후보:").contains("새봄 4월 대비 6월 심사 변화율").contains("단답형(숫자 하나)");
        assertThat(in).doesNotContain(b.flaws().get(0).what()).doesNotContain(b.flaws().get(0).where()).doesNotContain(b.flaws().get(0).evidence());
        assertThat(in).doesNotContain(b.conclusion().wrong()).doesNotContain("퍼센트포인트").doesNotContain("trapKeywords");
        assertThat(in).doesNotContain("expected").doesNotContain(b.cases().get(1).situation()).doesNotContain(b.cases().get(1).basis());
        assertThat(in).doesNotContain("총 25").doesNotContain("정답");
    }

    @Test
    @DisplayName("문항 글이 설계도의 보기 후보와 다르면 fail(문항 글 단계로), 설명 문장이 낀 보기나 정답 보기가 빠진 것도 잡는다")
    void questionTextCheck() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        assertThat(BizQuestionService.check(b, TaskPipelineServiceTest.bizQuestions()).ok()).isTrue();

        var qs = new ArrayList<>(TaskPipelineServiceTest.bizQuestions().questions());
        qs.set(0, new BizQuestionsDto.Question("Q1", "Q1. 배정 후보는?", List.of("여름만 후보", "둘 다 후보", "둘 다 보류", "새봄이 낫다는 설명 문장이 붙은 보기")));
        var bad = BizQuestionService.check(b, new BizQuestionsDto(qs));
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).contains("정답 보기").contains("새봄만 후보");
        assertThat(BizPipelineService.owners(bad)).containsExactly(Stage.QUESTIONS);
    }

    // ── 값 비교(단위 맞춤)와 기계적 수정 ─────────────────────────────

    @Test
    @DisplayName("값 비교: 0.5 와 50퍼센트, 1,050 과 1050원은 같은 값이고 다른 값은 다르다")
    void unitNormalizedComparison() {
        assertThat(ValueService.same("0.5", "50%")).isTrue();
        assertThat(ValueService.same("50퍼센트", "0.5")).isTrue();
        assertThat(ValueService.same("1,050건", "1050")).isTrue();
        assertThat(ValueService.same("25", "30")).isFalse();
        assertThat(ValueService.plain("25%")).isEqualTo("25");
        assertThat(ValueService.plain("1,050건")).isEqualTo("1050");
        assertThat(ValueService.plain("새봄만 후보")).isEqualTo("새봄만 후보");
    }

    @Test
    @DisplayName("보기에 다른 문항의 정답이 있는지 단위를 맞춰 비교한다: 다른 문항 정답이 50퍼센트이면 보기 0.5 도 걸린다")
    void choiceLeaksAreComparedByValue() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var shortQ = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q1. 비율은?", List.of(), List.of("50%"), "1", List.of());
        var single = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SINGLE, "Q2. 어느 것?",
                List.of(new BizDraftDto.Choice("0.5", true), new BizDraftDto.Choice("0.7", false), new BizDraftDto.Choice("0.9", false), new BizDraftDto.Choice("0.1", false)), List.of(), "", List.of());
        var r = AnswerChoiceService.check(b, List.of(shortQ, single));
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("Q2 보기 '0.5'").contains("Q1 의 정답 값");
        var clean = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SINGLE, "Q2. 어느 것?",
                List.of(new BizDraftDto.Choice("0.3", true), new BizDraftDto.Choice("0.7", false), new BizDraftDto.Choice("0.9", false), new BizDraftDto.Choice("0.1", false)), List.of(), "", List.of());
        assertThat(AnswerChoiceService.check(b, List.of(shortQ, clean)).ok()).isTrue();
    }

    @Test
    @DisplayName("기계적 수정(코드): 정답 키 값은 단위를 떼 숫자만 남기고, 정답 보기는 사례의 기대 결과와 가장 같은 보기 하나로 정한다. 판단 지점 사례는 문항으로 만들지 않는다")
    void mechanicalFixes() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        var judgmentCase = new BlueprintDto.Case("Q3", "먼저 할 작업", "안내문", "근거", "먼저 할 작업", List.of("안내문", "상담", "둘 다"), true);
        var cases = new ArrayList<>(b.cases());
        cases.set(2, judgmentCase);
        BlueprintDto withJudgment = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);
        assertThat(BizQuestionService.isJudgment(withJudgment, judgmentCase)).isTrue(); // 설계도 판단 지점("먼저 할 작업")과 1:1
        assertThat(BizQuestionService.ordered(withJudgment)).extracting(BlueprintDto.Case::name).containsExactly("Q1", "Q2"); // 문항 글 단계에도 가지 않는다
        assertThat(BizQuestionService.input(withJudgment, "글", "")).doesNotContain("먼저 할 작업");

        var questions = new BizQuestionsDto(List.of(
                new BizQuestionsDto.Question("Q1", "Q1. 배정 후보는?", List.of("여름만 후보", "새봄만 후보", "둘 다 후보", "둘 다 보류")),
                new BizQuestionsDto.Question("Q2", "Q2. 변화율은?", List.of())));
        var answer = new com.pair.domain.taskgen.dto.BizAnswerDto("## 필수 항목", List.of(
                new com.pair.domain.taskgen.dto.BizAnswerDto.Key("Q2", List.of("25%", "25.0 퍼센트"), "1", List.of(), List.of())));
        var qs = BizQuestionService.assemble(withJudgment, questions, answer);

        assertThat(qs).hasSize(2);
        assertThat(qs.get(0).choices()).extracting(BizDraftDto.Choice::correct).containsExactly(false, true, false, false); // 정답 보기는 기대 결과로 정한다(순서가 달라도)
        assertThat(qs.get(1).accepted()).containsExactly("25", "25.0"); // 단위를 뗀다
        assertThat(qs.get(0).type()).isEqualTo(BizDraftDto.Type.SINGLE);
        assertThat(qs.get(1).type()).isEqualTo(BizDraftDto.Type.SHORT);
    }

    @Test
    @DisplayName("사실·결론 문항은 judgment 가 true 로 적혀 있어도 건드리지 않는다: 설계도 판단 지점과 이어지지 않으면 판단 지점이 아니고, 숫자가 겹치는 보기가 여러 개여도 정답 보기는 하나만")
    void factAndConclusionQuestionsAreNeverTouched() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint();
        // 결론 문항: 모델이 judgment=true 로 잘못 적었지만 설계도 판단 지점("먼저 할 작업")과 이어지지 않는다. 보기마다 같은 숫자(80퍼센트)가 들어 있다
        var expected = "두 차수 모두 80퍼센트 목표를 달성했다.";
        var choices = List.of(expected, "1차만 80퍼센트 목표를 달성했다.", "2차만 80퍼센트 목표를 달성했다.", "두 차수 모두 80퍼센트 목표에 못 미쳤다.");
        var conclusion = new BlueprintDto.Case("Q1", "차수별 목표 달성", expected, "근거", "각 조사 차수의 목표 달성 여부", choices, true);
        assertThat(BizQuestionService.isJudgment(b, conclusion)).isFalse();

        BlueprintDto bp = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), List.of(conclusion));
        var qs = BizQuestionService.assemble(bp, new BizQuestionsDto(List.of(new BizQuestionsDto.Question("Q1", "Q1. 목표 달성 여부는?", choices))),
                new com.pair.domain.taskgen.dto.BizAnswerDto("c", List.of()));

        assertThat(qs).hasSize(1);
        assertThat(qs.get(0).choices()).extracting(BizDraftDto.Choice::correct).containsExactly(true, false, false, false); // 정확히 하나
        assertThat(BizQuestionService.check(bp, new BizQuestionsDto(List.of(new BizQuestionsDto.Question("Q1", "Q1", choices)))).ok()).isTrue();
        // 숫자만 적힌 보기는 단위를 맞춘 같은 값으로 하나만 고른다
        assertThat(BizQuestionService.correctIndex(List.of("20", "25퍼센트", "0.25", "30"), "25")).isEqualTo(1);
    }

    @Test
    @DisplayName("형식 검사가 정답 보기 개수 때문에 실패해도 나머지 독립 검사는 건너뛰지 않고 모두 돈다(문제를 한꺼번에 본다)")
    void remainingChecksRunWhenCorrectCountFails() {
        BizDraftDto d = BizFixtures.good("soft");
        var q0 = d.questions().get(0);
        var allCorrect = q0.choices().stream().map(c -> new BizDraftDto.Choice(c.content(), true)).toList();
        BizDraftDto bad = BizFixtures.edit(d, b -> b.questions(List.of(new BizDraftDto.QuestionDraft(q0.type(), q0.content(), allCorrect, q0.accepted(), q0.calc(), q0.sources(), q0.commonWrongAnswers()),
                d.questions().get(1), d.questions().get(2), d.questions().get(3))));

        var results = BizCheckService.forDraft(new PdfService(), new BizFixtures.FakeSolver(), "gpt-6.1-terra", bad).stream().map(c -> c.run(bad)).toList();
        var names = BizCheckService.forDraft(new PdfService(), new BizFixtures.FakeSolver(), "gpt-6.1-terra", bad).stream().map(GenCheckService::name).toList();

        assertThat(results.get(names.indexOf(BizCheckService.FORMAT)).ok()).isFalse();
        assertThat(results.get(names.indexOf(BizCheckService.FORMAT)).detail()).contains("정답 보기가 1개");
        assertThat(results).noneMatch(r -> r.detail() != null && r.detail().contains("형식이 맞지 않아 확인하지 않았습니다"));
        assertThat(results.get(names.indexOf(BizCheckService.FORMAT)).stages()).containsExactly("QUESTIONS", "ANSWER");
        assertThat(BizPipelineService.owners(results.get(names.indexOf(BizCheckService.FORMAT)))).contains(Stage.QUESTIONS);
    }
}
