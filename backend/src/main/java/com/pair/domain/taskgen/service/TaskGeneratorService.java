package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * 과제 생성 순서(Template Method): 프롬프트 → AI 호출 → 검사 → (실패하면 실패 내용을 붙여 1번만 다시 생성) → 저장.
 * 하위 클래스는 프롬프트, 출력 형식, 검사 목록, 저장 방법만 정한다.
 * AI 호출이 실패하면(첫 호출) 예외를 그대로 던지고 저장하지 않는다.
 * 검사가 끝내 실패해도 저장한다(검수 전 상태라 어디에도 보이지 않는다).
 * 검사 등급: fail 만 다시 생성을 일으키고, warn 은 저장한 뒤 검수자에게 알리기만 한다. 참고 지표(AI 단독 풀이)는 항상 ok(warn 등급)라 다시 생성을 일으키지 않는다.
 */
public abstract class TaskGeneratorService<D> {

    /**
     * 생성 프롬프트의 맨 앞에 붙는 핵심 정의와 과제 다섯 요소. 기준 문서(pAIr-docs 설계/좋은_과제_기준.md) 1장, 2장 E1~E5, Q3.
     * 프롬프트에서 '%' 를 쓰지 않는다(SYSTEM.formatted 로 합쳐진다).
     */
    static final String PURPOSE = """
            [과제의 목적] 사람이 판단할 지점과 AI 에 맡기면 좋은 지점이 섞여 있어서, 그것을 구별하고 AI 에 잘 지시하고 자료와 대조해 검증한 사람이 더 좋은 결과를 내는 과제를 만듭니다. \
            AI 가 혼자 풀 수 있어도 됩니다. 대신 AI 답을 그대로 받으면 놓치는 것(숨은 결함)이 있어야 하고, 꼼꼼히 대조하면 찾을 수 있어야 합니다. 그렇다고 AI 를 써도 못 풀 만큼 꼬아서 어렵게 만들면 안 됩니다.
            과제는 다섯 요소를 모두 갖습니다. \
            E1 맡길 거리(잘 지시하면 AI 가 빠르게 해내는 분량). \
            E2 판단 지점(모호한 요구나 트레이드오프처럼 사람이 정할 것. 정답이 하나가 아니어도 되지만 판단 근거가 자료에 있음). \
            E3 숨은 결함(AI 기본 답이 그럴듯하지만 자료나 테스트와 대조해야만 틀린 게 보이는 곳). \
            E4 검증 수단(대조할 자료, 돌려 볼 테스트가 과제 안에 있음). \
            E5 이해 확인 후속 질문(왜 그렇게 정했나, 조건이 바뀌면, 이대로 내보내겠나).
            """;

    /** 명세와 답 노출의 경계. 기준 문서 3장 '명세와 답 노출의 경계'(M1, M5). 양쪽 생성기가 같은 문장을 쓴다 */
    static final String BOUNDARY = """
            [명세와 답 노출의 경계] 줘도 되는 것은 무엇이 맞는 결과인지입니다(입력과 기대 출력의 예, 항상 성립해야 하는 조건, 현상과 재현 값, 측정 범위나 집계 정의 같은 사실). \
            주면 안 되는 것은 왜 틀렸는지와 어디를 어떻게 고칠지입니다(원인, 결함 위치, 고치는 방법, 계산 순서나 절차, "~해야 한다", "~해서는 안 된다", "~만으로 판단하지 않는다" 같은 지시. 문항 문구도 계산법을 지정하지 않습니다). \
            결함을 피하는 데 꼭 필요한 기대 결과(예: 끝나는 시각에 바로 시작하는 예약)는 여러 예 가운데 하나로 두고, 따로 떼어 규칙 문장으로 강조하지 않습니다(M3·E3: 숨은 결함을 피하는 기대 결과가 요구사항에 "각 구간 이름은 오름차순이다", "반환 건수는 n과 전체 중 작은 값이다" 같은 한 줄 규칙으로 적히면 결함이 숨겨지지 않은 것입니다. 이런 결과는 입력→기대 출력 예에서만 보이게 합니다). 규칙 문장("~하면 예외가 발생한다"), 원인 문장, "~하지 않는다"·"~로 잡히지 않는다" 꼴, 값을 합치는 방법(차이, 비율, 가중치)은 쓰지 않습니다. 정의는 무엇을 세는지(대상과 범위)까지만 쓰고, 두 대상이 서로 다르다고 직접 짚는 문장("A와 B는 다르다")과 "원인:" 표지도 쓰지 않습니다.
            """;

    public static final String STEP_GENERATING = "생성 중";
    public static final String STEP_CHECKING = "확인 중";
    public static final String STEP_REGENERATING = "다시 생성 중";
    public static final String STEP_SAVING = "저장 중";

    /** 생성 결과. passed 는 모든 검사 통과 여부 */
    public record Outcome(String taskCode, boolean passed, int attempts, List<GenCheckResultDto> checks) {}

    /** _gen/report.json 내용 (설계 7장) */
    public record GenReport(String model, int attempts, long seconds, long inputTokens, long outputTokens,
            boolean passed, TaskGenRequest form, List<GenCheckResultDto> checks, String retryError,
            java.util.Map<String, Object> details, List<AttemptLog> attemptLog) {
        /** 시도별 기록 없이 만든다(테스트·옛 코드용) */
        public GenReport(String model, int attempts, long seconds, long inputTokens, long outputTokens,
                boolean passed, TaskGenRequest form, List<GenCheckResultDto> checks, String retryError,
                java.util.Map<String, Object> details) {
            this(model, attempts, seconds, inputTokens, outputTokens, passed, form, checks, retryError, details, List.of());
        }
    }

    /**
     * 시도 1번의 기록(report.json 의 attemptLog). 마지막 시도 검사만 report 의 checks 에 남으므로,
     * 앞 시도가 왜 실패해 다시 만들었는지 여기서 본다. problems 는 통과하지 못한 검사(fail 과 warn 모두, level 로 구분)
     */
    public record AttemptLog(int number, boolean passed, long seconds, List<Problem> problems) {}

    /** 통과하지 못한 검사 1개의 이름과 짧은 설명(최대 200자) */
    public record Problem(String name, String level, String detail) {}

    private static final int PROBLEM_CHARS = 200;

    private static AttemptLog attemptLog(int number, long startedNanos, List<GenCheckResultDto> results) {
        List<Problem> problems = results.stream().filter(c -> !c.ok())
                .map(c -> new Problem(c.name(), c.level(), c.detail() == null || c.detail().length() <= PROBLEM_CHARS ? c.detail()
                        : c.detail().substring(0, PROBLEM_CHARS) + "…"))
                .toList();
        boolean passed = results.stream().noneMatch(GenCheckResultDto::needsRegeneration);
        return new AttemptLog(number, passed, Math.round((System.nanoTime() - startedNanos) / 1e9), problems);
    }

    /** 확인이 모두 끝난 뒤 최종 결과에 덧붙이는 검사·자료. files 는 저장 때 함께 올린다 */
    public record Extra(List<GenCheckResultDto> checks, java.util.Map<String, Object> details, java.util.Map<String, String> files) {
        public static final Extra NONE = new Extra(List.of(), java.util.Map.of(), java.util.Map.of());
    }

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    /** 단계별 생성(기본). null 이거나 pair.taskgen.pipeline=oneshot 이면 옛 방식(한 번에 과제 전체) */
    private final TaskPipelineService<D> pipeline;

    protected TaskGeneratorService(TaskGenAiService ai, TaskGenProperties props) {
        this(ai, props, null);
    }

    protected TaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, TaskPipelineService<D> pipeline) {
        this.ai = ai;
        this.props = props;
        this.pipeline = pipeline;
    }

    private boolean staged() {
        return pipeline != null && props.staged();
    }

    /** 옛 방식: 한 번의 호출이 과제 전체를 쓰고, 실패하면 실패 내용을 붙여 처음부터 다시 쓴다 */
    private final class OneShotRun implements TaskPipelineService.Run<D> {
        private final TaskGenRequest form;
        private final String system;
        private long in, out;

        OneShotRun(TaskGenRequest form) {
            this.form = form;
            this.system = system(form);
        }

        private D call(List<GenCheckResultDto> failed) {
            TaskGenAiService.Output<D> o = ai.generate(props.model(), system, user(form, failed), draftType());
            in += o.inputTokens();
            out += o.outputTokens();
            return o.value();
        }

        @Override
        public D create(Consumer<String> onStep) {
            onStep.accept(STEP_GENERATING);
            return call(List.of());
        }

        @Override
        public D redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            onStep.accept(STEP_REGENERATING);
            return call(failed);
        }

        @Override
        public List<GenCheckResultDto> checks() {
            return List.of();
        }

        @Override
        public List<com.pair.domain.taskgen.dto.StageUsageDto> usage() {
            return List.of(new com.pair.domain.taskgen.dto.StageUsageDto("ONESHOT", props.model(), props.effort(), in, out, 0));
        }

        @Override
        public List<String> redone() {
            return List.of();
        }
    }

    /** 판정 결과를 후속 검사 결과 맨 앞에 붙인다 */
    protected static Extra withCheck(GenCheckResultDto first, Extra rest) {
        List<GenCheckResultDto> all = new java.util.ArrayList<>(List.of(first));
        all.addAll(rest.checks());
        return new Extra(all, rest.details(), rest.files());
    }

    protected abstract String system(TaskGenRequest form);

    /** failed: 앞선 시도에서 실패한 검사들. 첫 시도는 빈 목록 */
    protected abstract String user(TaskGenRequest form, List<GenCheckResultDto> failed);

    protected abstract Class<D> draftType();

    /** 이 Draft 에 대한 검사 목록 (실행 결과를 검사끼리 나눠 쓰도록 Draft 마다 새로 만든다) */
    protected abstract List<GenCheckService<D>> checks(TaskGenRequest form, D draft);

    /** report.json 에 더 남길 내용 (예: 비개발 함정 정보) */
    protected java.util.Map<String, Object> details(D draft) {
        return java.util.Map.of();
    }

    /**
     * 시도마다(첫 생성, 다시 생성) 다른 검사가 끝난 뒤 그 Draft 와 검사 결과를 보고 덧붙일 검사·자료를 만든다. 기본은 없음. 던지지 않는다.
     * 여기서 만든 fail 검사는 다시 생성을 일으킨다(지금 쓰는 참고 지표 AI 단독 풀이는 fail 을 만들지 않는다. 비용이 큰 검사를 다른 검사가 통과한 뒤에만 돌릴 때 쓴다)
     */
    protected Extra afterChecks(TaskGenRequest form, D draft, List<GenCheckResultDto> results) {
        return Extra.NONE;
    }

    /** extraFiles 가 있는 하위 클래스만 덮어쓴다 */
    protected String save(TaskGenRequest form, D draft, GenReport report, long adminId, java.util.Map<String, String> extraFiles) {
        return save(form, draft, report, adminId);
    }

    /** Draft 에서 모델이 정한 역량(검증 전 값) */
    protected abstract List<String> draftCompetencies(D draft);

    /** 한 트랜잭션으로 저장하고 과제 code 를 돌려준다 */
    protected abstract String save(TaskGenRequest form, D draft, GenReport report, long adminId);

    // ── 관문 검사(AI) 연결(단계별 생성 전용). 하위 클래스가 Draft 모양에 맞게 채운다 ─────────────────────────────

    /** 관문 검사 점검 하나의 입력. 이 직군에 안 맞으면 null */
    protected String checkInput(TaskReviewService.Check check, TaskGenRequest form, BlueprintDto bp, D draft) {
        return null;
    }

    /** 관문 검사(답 노출·정답 키·테스트 근거·다른 풀이)를 하는 직군인가. false 면 코드 검사만 한다 */
    protected boolean reviewable() {
        return false;
    }

    protected boolean devTrack() {
        return false;
    }

    /** E6·M6 관련 적은 자료의 응시자에게 보이는 글(경로 → 글). 단계별 생성의 Dev/Biz 하위 클래스가 채운다. 없으면 빈 맵 */
    protected Map<String, String> fillerTexts(D draft, BlueprintDto bp) {
        return Map.of();
    }

    /** "다른 풀이" 점검: 검수자가 낸 풀이를 숨은 테스트로 실제로 돌려 떨어지면 위반으로 돌려준다(DEV). notes 는 report 용 */
    protected ReviewExtra altRun(D draft, List<DevDraftDto.FileDraft> alt) {
        return new ReviewExtra(List.of(), Map.of());
    }

    protected record ReviewExtra(List<Violation> violations, Map<String, Object> notes) {}

    /** 다시 만들기 예산: 글 문제 2번 + 테스트·정답 문제 2번(종류별로 따로), 설계도부터 새로 1번(비용 상한 안에서만). 최대 5번. 그래도 안 되면 실패 이유와 함께 검수 전(HIDDEN)으로 저장 */
    static final int MAX_TEXT_REGENS = 2; // 글 문제(답 노출·일관성 등: 작성·문항 글 단계)
    static final int MAX_TEST_REGENS = 2; // 테스트·정답 문제(실행 검사·정답 키·테스트 근거 등: 정답 쪽·시작 코드 단계)
    static final String COST_CAP_CHECK = "비용 상한";
    static final int MAX_RESTARTS = 1;
    static final String STEP_REVIEW = "관문 검사 중";
    static final String GATE_SKIPPED = "관문 검사 호출 실패";
    static final String RESTART_CHECK = "설계도부터 다시 만듦";
    static final String WARN_CHECK = "일관성·품질 경고";

    private static boolean hasFail(List<GenCheckResultDto> results) {
        return results.stream().anyMatch(GenCheckResultDto::needsRegeneration);
    }

    private static String models(List<StageUsageDto> usage, String fallback) {
        String joined = usage.stream().map(StageUsageDto::model).distinct().collect(Collectors.joining(", "));
        return joined.isEmpty() ? fallback : joined;
    }

    /** 관문 1번의 실행 결과 */
    private record GateRun(TaskReviewService.Gate gate, GenCheckResultDto result, List<Violation> warnings, int dropped, Map<String, Object> notes) {}

    private List<GateRun> runGates(TaskReviewService reviewer, TaskGenRequest form, BlueprintDto bp, D draft) {
        List<GateRun> out = new ArrayList<>();
        for (TaskReviewService.Gate g : TaskReviewService.gates(devTrack())) {
            if (g.check() == TaskReviewService.Check.FILLER && !props.materialGate()) continue; // E6·M6 가 꺼져 있으면 점검하지 않는다
            String input = g.check() == TaskReviewService.Check.FILLER ? TaskReviewService.fillerInput(bp, fillerTexts(draft, bp)) : checkInput(g.check(), form, bp, draft);
            if (input == null) continue;
            List<Violation> violations;
            int dropped = 0;
            Map<String, Object> notes = Map.of();
            if (g.check() == TaskReviewService.Check.ALT) {
                ReviewExtra x = altRun(draft, reviewer.alternative(input));
                violations = x.violations();
                notes = x.notes();
            } else {
                TaskReviewService.Findings f = reviewer.run(g.check(), g.stage(), input);
                violations = f.violations();
                dropped = f.dropped();
            }
            GateRun run = new GateRun(g, TaskReviewService.result(g, violations), violations.stream().filter(v -> !v.mustFix()).toList(), dropped, notes);
            out.add(run);
            if (!run.result().ok()) break; // 관문 하나가 실패하면 나머지는 돌리지 않는다(다시 만들면 결과가 바뀌므로 비용만 든다)
        }
        return out;
    }

    private static List<Map<String, Object>> gateRecords(List<GateRun> runs) {
        return runs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gate", r.gate().name());
            m.put("stage", r.gate().stage().name());
            m.put("ok", r.result().ok());
            m.put("detail", r.result().detail());
            if (r.dropped() > 0) m.put("droppedOutOfScope", r.dropped());
            if (!r.notes().isEmpty()) m.put("notes", r.notes());
            return m;
        }).toList();
    }

    public final Outcome generate(TaskGenRequest form, long adminId, Consumer<String> onStep) {
        EXTRA_USAGE.set(new ArrayList<>());
        RUN_TALLY.set(new long[3]);
        try {
            return generateInner(form, adminId, onStep);
        } finally {
            EXTRA_USAGE.remove();
            RUN_TALLY.remove();
        }
    }

    /** 코드 실행 1번의 기록(생성기 전용 실행 장치가 부른다): 줄 서서 기다린 밀리초와 실제 실행 밀리초. 이 생성 작업의 report timing 에 합쳐진다 */
    static void recordRun(long waitMillis, long runMillis) {
        long[] t = RUN_TALLY.get();
        if (t == null) return;
        t[0]++;
        t[1] += waitMillis;
        t[2] += runMillis;
    }

    private static final ThreadLocal<long[]> RUN_TALLY = new ThreadLocal<>();

    /** 단계별 시간(초): 모델 호출(단계별 합과 호출 수)과 코드 실행(횟수, 대기, 실행) */
    private static Map<String, Object> timing(List<StageUsageDto> usage) {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (StageUsageDto u : usage) byStage.merge(u.stage(), u.seconds(), Long::sum);
        t.put("modelCalls", usage.size());
        t.put("modelSeconds", usage.stream().mapToLong(StageUsageDto::seconds).sum());
        t.put("modelSecondsByStage", byStage);
        long[] r = RUN_TALLY.get() == null ? new long[3] : RUN_TALLY.get();
        t.put("runCount", r[0]);
        t.put("runWaitSeconds", Math.round(r[1] / 100.0) / 10.0);
        t.put("runSeconds", Math.round(r[2] / 100.0) / 10.0);
        return t;
    }

    /** 검사 안에서 부른 모델(BIZ 풀이 검사 등)의 토큰을 이 생성 작업의 report 에 남긴다 */
    protected static void recordUsage(StageUsageDto usage) {
        List<StageUsageDto> l = EXTRA_USAGE.get();
        if (l != null) l.add(usage);
    }

    private static final ThreadLocal<List<StageUsageDto>> EXTRA_USAGE = new ThreadLocal<>();

    /**
     * 생성 → (코드 검사 → 관문 검사) → 실패하면 실패를 낸 단계만 다시 → 관문 전체를 처음부터 다시. 예산이 다하면 이유와 함께 저장한다.
     * AI 가 글을 고치지 않는다(설계 2장): 고치는 단계가 없고, 기계적 수정은 조립 코드가 한다
     */
    private Outcome generateInner(TaskGenRequest form, long adminId, Consumer<String> onStep) {
        long started = System.nanoTime();
        boolean staged = staged();
        TaskPipelineService.Run<D> run = staged ? pipeline.start(form) : new OneShotRun(form);
        StageCallService reviewCalls = new StageCallService(ai, props, devTrack());
        TaskReviewService reviewer = new TaskReviewService(reviewCalls);
        boolean gated = staged && reviewable();

        List<AttemptLog> attemptLog = new ArrayList<>();
        List<Map<String, Object>> gateRounds = new ArrayList<>(); // 시도마다 코드 검사 실패와 관문 결과
        List<Map<String, Object>> regenerations = new ArrayList<>(); // 다시 만든 이력: 어느 단계를 왜
        String retryError = null;
        boolean gateSkipped = false;
        int attempts = 1, stageRegens = 0, textRegens = 0, testRegens = 0, restarts = 0;
        boolean capStopped = false;
        List<Map<String, Object>> costEstimates = new ArrayList<>(); // 다시 만들기 전 비용 어림과 근거
        String capDetail = "";
        double cap = props.costCap(devTrack(), form.minutes()); // 직군·제한시간별 상한(report cost.capUsd 에 남는다)

        long attemptStarted = System.nanoTime();
        List<Map<String, Object>> outputLimitLog = new ArrayList<>(); // 출력 한도 끊김으로 단계부터 이어 만든 기록
        int[] used = new int[3]; // 이어 만들기도 단계 다시 만들기 예산을 쓴다: {글, 테스트, 설계도부터}
        // 첫 생성이 실패하면 예외를 그대로 던지고 저장하지 않는다. 단, 출력 한도 끊김은 예산 안에서 그 단계부터 이어 만든다
        D draft = withResume(run, () -> run.create(onStep), onStep, used, outputLimitLog);
        textRegens = used[0];
        testRegens = used[1];
        restarts = used[2];
        double createCost = StageCallService.cost(props, run.usage()); // 처음부터 다시 만들 때 드는 비용의 어림값
        List<GenCheckResultDto> results;
        List<GateRun> gates;
        while (true) {
            double checkStart = spent(run, reviewCalls);
            onStep.accept(STEP_CHECKING);
            results = runChecks(form, draft, run);
            gates = List.of();
            Map<String, Object> round = new LinkedHashMap<>();
            round.put("attempt", attempts);
            round.put("codeChecksFailed", results.stream().filter(GenCheckResultDto::needsRegeneration).map(GenCheckResultDto::name).toList());
            if (gated && !hasFail(results)) { // 코드 검사를 통과한 뒤에만 AI 관문을 돈다(비용)
                onStep.accept(STEP_REVIEW);
                try {
                    gates = runGates(reviewer, form, run.blueprint(), draft);
                    round.put("gates", gateRecords(gates));
                } catch (RuntimeException e) { // 관문 호출이 실패하면 과제를 버리지 않고 경고로 남긴다
                    retryError = "관문 검사 호출 실패: " + e.getMessage();
                    gateSkipped = true;
                    round.put("gates", "호출 실패: " + e.getMessage());
                }
            } else if (gated) {
                round.put("gates", "코드 검사가 실패해 돌리지 않음");
            }
            gateRounds.add(round);
            double checkCost = spent(run, reviewCalls) - checkStart; // 이번 시도의 검사(관문·풀이) 비용: 다시 만들면 한 번 더 든다
            List<GenCheckResultDto> all = new ArrayList<>(results);
            gates.forEach(g -> all.add(g.result()));
            attemptLog.add(attemptLog(attempts, attemptStarted, all));
            List<GenCheckResultDto> failed = all.stream().filter(GenCheckResultDto::needsRegeneration).toList();
            if (failed.isEmpty() || gateSkipped) break;

            // 다시 만들 방법: 실패를 낸 단계만(예산 3번), 설계도 문제거나 예산이 다했으면 설계도부터(1번), 그것도 다했으면 저장
            boolean natural = staged && run.restartsFrom(failed);
            java.util.Set<String> kinds = staged ? run.kinds(failed) : java.util.Set.of("test");
            boolean textLeft = textRegens < MAX_TEXT_REGENS, testLeft = testRegens < MAX_TEST_REGENS;
            boolean byStage = staged && !natural && kinds.stream().allMatch(k -> k.equals("text") ? textLeft : testLeft);
            if (!byStage && restarts >= MAX_RESTARTS) break;
            boolean restart = !byStage;
            List<GenCheckResultDto> redoArg = failed;
            if (restart && staged && !natural) {
                redoArg = List.of(new GenCheckResultDto(RESTART_CHECK, false, failed.stream().map(c -> c.name() + ": " + c.detail()).collect(Collectors.joining(" / "))));
            }
            // 비용 상한: 다음 다시 만들기 비용 = 다시 돌 단계들이 바로 앞 시도에서 쓴 비용 + 검사 비용. 다시 돌 단계를 모르면 처음부터 만든 비용
            double spent = spent(run, reviewCalls);
            List<String> planned = staged ? run.plannedStages(redoArg) : List.of();
            Map<String, Double> stageUsd = new LinkedHashMap<>();
            for (String st : planned) stageUsd.put(st, lastStageCost(run.usage(), st));
            double regenCost = planned.isEmpty() ? createCost : stageUsd.values().stream().mapToDouble(Double::doubleValue).sum();
            double estimate = regenCost + checkCost;
            Map<String, Object> est = new LinkedHashMap<>(); // report.json 에 남기는 어림 근거
            est.put("attempt", attempts);
            est.put("method", planned.isEmpty() ? "처음부터 만든 비용 + 검사 비용" : "다시 돌 단계가 바로 앞 시도에서 쓴 비용 + 검사 비용");
            est.put("stages", planned);
            est.put("stageUsd", stageUsd.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> round4(e.getValue()), (a, b) -> a, LinkedHashMap::new)));
            est.put("regenUsd", round4(regenCost));
            est.put("checkUsd", round4(checkCost));
            est.put("spentUsd", round4(spent));
            est.put("estimateUsd", round4(estimate));
            costEstimates.add(est);
            if (spent + estimate > cap) {
                capStopped = true;
                capDetail = String.format(java.util.Locale.ROOT, "지금까지 $%.2f, 다음 다시 만들기 예상 $%.2f → 상한 $%.2f 초과. 마지막 실패: %s", spent, estimate, cap,
                        failed.stream().map(c -> c.name() + ": " + clip(c.detail())).collect(Collectors.joining(" / ")));
                break;
            }
            if (byStage) {
                stageRegens++;
                if (kinds.contains("text")) textRegens++;
                if (kinds.contains("test")) testRegens++;
            } else restarts++;
            attemptStarted = System.nanoTime();
            try {
                List<GenCheckResultDto> redoFinal = redoArg;
                used[0] = textRegens;
                used[1] = testRegens;
                used[2] = restarts;
                draft = withResume(run, () -> run.redo(redoFinal, onStep), onStep, used, outputLimitLog);
                textRegens = used[0];
                testRegens = used[1];
                restarts = used[2];
            } catch (RuntimeException e) {
                // 다시 만들기가 실패해도 이미 만든 결과는 버리지 않고 실패 표시와 함께 저장한다
                retryError = String.valueOf(e.getMessage());
                attemptLog.add(new AttemptLog(attempts + 1, false, Math.round((System.nanoTime() - attemptStarted) / 1e9),
                        List.of(new Problem("다시 생성 실패", GenCheckResultDto.FAIL, String.valueOf(e.getMessage())))));
                break;
            }
            attempts++;
            Map<String, Object> regen = new LinkedHashMap<>();
            regen.put("n", regenerations.size() + 1);
            regen.put("kind", restart ? "blueprint" : "stages");
            regen.put("stages", run.redone());
            regen.put("because", failed.stream().map(c -> Map.of("name", c.name(), "detail", clip(c.detail()))).toList());
            regenerations.add(regen);
        }

        // 마지막 Draft 의 결과 정리: 코드 검사 + 관문 + 경고(일관성, Q)
        List<GenCheckResultDto> finalChecks = new ArrayList<>(results);
        gates.forEach(g -> finalChecks.add(g.result()));
        if (capStopped) finalChecks.add(new GenCheckResultDto(COST_CAP_CHECK, false, capDetail));
        if (gateSkipped) finalChecks.add(new GenCheckResultDto(GATE_SKIPPED, false, "관문 검사를 하지 못했어요(" + retryError + ")", GenCheckResultDto.WARN));
        Map<String, Object> warnLog = new LinkedHashMap<>();
        if (gated && !gateSkipped && !hasFail(finalChecks)) {
            List<Violation> warnings = new ArrayList<>();
            gates.forEach(g -> warnings.addAll(g.warnings()));
            try {
                String input = checkInput(TaskReviewService.Check.CONSISTENCY, form, run.blueprint(), draft);
                if (input != null) warnings.addAll(reviewer.run(TaskReviewService.Check.CONSISTENCY, Stage.REVIEW, input).violations());
            } catch (RuntimeException e) {
                warnLog.put("consistencyError", String.valueOf(e.getMessage()));
            }
            for (var v : warnings) {
                finalChecks.add(new GenCheckResultDto(WARN_CHECK, false, v.item() + " " + v.location() + ": " + clip(v.quote()) + " → " + v.fix(), GenCheckResultDto.WARN));
            }
            warnLog.put("count", warnings.size());
        }
        // 참고 지표(AI 단독 풀이)는 관문을 통과한 뒤 항상 돌린다. 실패한 과제는 건너뛴다고 남긴다
        Extra extra = afterChecks(form, draft, finalChecks);
        finalChecks.addAll(extra.checks());
        results = finalChecks;

        Map<String, Object> detail = new LinkedHashMap<>(details(draft));
        detail.putAll(extra.details());
        detail.put("solo", soloOutcome(extra.checks())); // 난이도 참고 지표(AI 단독 풀이): missed·caught·skipped(이유)·error(이유)
        List<StageUsageDto> usage = new ArrayList<>(run.usage());
        usage.addAll(reviewCalls.usage());
        usage.addAll(EXTRA_USAGE.get()); // 검사 안에서 부른 모델(BIZ 풀이 검사 등)
        detail.put("timing", timing(usage));
        Map<String, Object> soloTokens = extra.details().get("soloTokens") instanceof Map<?, ?> m ? castTokens(m) : Map.of();
        double soloCost = soloTokens.isEmpty() ? 0 : props.cost(props.soloModel() == null || props.soloModel().isBlank() ? "gpt-6-luna" : props.soloModel(),
                ((Number) soloTokens.get("input")).longValue(), ((Number) soloTokens.get("output")).longValue());
        if (staged) { // 단계별 모델·토큰·시간(비용 확인용), 관문 결과, 다시 만든 이력
            detail.put("stages", usage);
            Map<String, Object> limit = new LinkedHashMap<>(); // 출력 한도 끊김: 추론 강도를 낮춰 받은 호출 수, 낮춰도 끊긴 단계 수, 이어 만든 기록
            limit.put("effortLowered", usage.stream().filter(u -> StageCallService.OUTPUT_LIMIT_LOWERED.equals(u.outputLimit())).count());
            limit.put("stageFailures", usage.stream().filter(u -> StageCallService.OUTPUT_LIMIT_FAILED.equals(u.outputLimit())).count());
            limit.put("resumes", outputLimitLog);
            detail.put("outputLimit", limit);
            detail.put("blueprintRetries", run.blueprintRetries()); // 설계도 형식 검사로 다시 받은 이유(점검: taskgen-runs/_verify/prompt-audit-2026-11-26.md)
            Map<String, Object> cost = new LinkedHashMap<>(); // 호출 비용 = 토큰 × 가격. byStage 는 단계(점검)별 합계
            Map<String, Double> byStageCost = new LinkedHashMap<>();
            for (StageUsageDto u : usage) byStageCost.merge(u.stage(), round4(props.cost(u.model(), u.inputTokens(), u.outputTokens())), Double::sum);
            if (soloCost > 0) byStageCost.put("SOLO", round4(soloCost));
            cost.put("totalUsd", round4(StageCallService.cost(props, usage) + soloCost));
            cost.put("capUsd", cap);
            cost.put("attempts", attempts);
            cost.put("stoppedByCap", capStopped);
            JobCallService saved = JobCallService.current();
            cost.put("reusedCalls", saved == null ? 0 : saved.reusedCount()); // 끊긴 작업을 이어 가며 저장된 결과를 다시 쓴 호출 수(그 비용은 위 합계에 한 번만 들어 있다)
            cost.put("estimates", costEstimates);
            cost.put("byStageUsd", byStageCost);
            detail.put("cost", cost);
            if (!run.redone().isEmpty()) detail.put("redoStages", run.redone());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("passed", results.stream().noneMatch(GenCheckResultDto::needsRegeneration));
            g.put("rounds", gateRounds);
            g.put("regenerations", regenerations);
            Map<String, Object> budget = new LinkedHashMap<>(); // 종류별로 쓴 다시 만들기: 글 문제(text), 테스트·정답 문제(test), 설계도부터(restarts)
            budget.put("textRegens", textRegens);
            budget.put("maxTextRegens", MAX_TEXT_REGENS);
            budget.put("testRegens", testRegens);
            budget.put("maxTestRegens", MAX_TEST_REGENS);
            budget.put("stageRegens", stageRegens);
            budget.put("restarts", restarts);
            budget.put("maxRestarts", MAX_RESTARTS);
            g.put("budget", budget);
            g.put("warnings", warnLog);
            detail.put("gates", g);
        }
        long in = usage.stream().mapToLong(StageUsageDto::inputTokens).sum();
        long out = usage.stream().mapToLong(StageUsageDto::outputTokens).sum();

        // warn 검사는 통과 여부에 영향을 주지 않는다(검수자에게 알리기만 한다)
        boolean passed = results.stream().noneMatch(GenCheckResultDto::needsRegeneration);
        onStep.accept(STEP_SAVING);
        long seconds = Math.round((System.nanoTime() - started) / 1e9);
        // 요청에 역량이 있으면 그대로, 비어 있으면 모델이 정한 값(검증해서 잘못된 key 는 버림)을 쓴다. 보고서 form 에도 이 값이 남는다
        TaskGenRequest saved = form.competencies() != null && !form.competencies().isEmpty() ? form
                : form.withCompetencies(TaskGenRequest.pickCompetencies(draftCompetencies(draft)));
        String code = save(saved, draft, new GenReport(models(usage, props.model()), attempts, seconds, in, out, passed, saved, results, retryError, detail, attemptLog), adminId, extra.files());
        return new Outcome(code, passed, attempts, results);
    }

    /** 지금까지 쓴 비용($): 단계 호출 + 관문 호출 + 검사 안의 풀이 호출 */
    private double spent(TaskPipelineService.Run<D> run, StageCallService reviewCalls) {
        return StageCallService.cost(props, run.usage()) + reviewCalls.cost() + StageCallService.cost(props, EXTRA_USAGE.get());
    }

    /**
     * 참고 지표(AI 단독 풀이) 결과를 report 에 남길 모양으로: status 는 missed(놓침: 목표 쪽)·caught(잡음)·skipped(건너뜀)·error(확인 못 함), reason 은 건너뛴·못 한 이유.
     * 이 검사는 항상 warn 이라 통과 여부에 영향을 주지 않는다
     */
    static Map<String, Object> soloOutcome(List<GenCheckResultDto> checks) {
        Map<String, Object> m = new LinkedHashMap<>();
        GenCheckResultDto c = checks.stream().filter(x -> SoloSolveService.NAME.equals(x.name()) || BizSoloSolveService.NAME.equals(x.name())).findFirst().orElse(null);
        if (c == null) {
            m.put("status", "skipped");
            m.put("reason", "단독 풀이 검사가 실행되지 않음");
            return m;
        }
        String d = c.detail() == null ? "" : c.detail();
        if (d.startsWith("건너뜀: ")) {
            m.put("status", "skipped");
            m.put("reason", d.substring("건너뜀: ".length()));
        } else if (d.startsWith(SoloSolveService.INVALID_PREFIX)) { // 컴파일 실패·결함과 상관없는 테스트 실패: 놓침 비율 분모에서 뺀다
            m.put("status", "invalid");
            m.put("reason", d.substring(SoloSolveService.INVALID_PREFIX.length()));
        } else if (d.contains("지나쳤")) m.put("status", "missed");
        else if (d.contains("잡았")) m.put("status", "caught");
        else {
            m.put("status", "error");
            m.put("reason", d);
        }
        return m;
    }

    /** stage 를 마지막으로 부른 호출 하나의 비용($). 부른 적 없으면 0 */
    private double lastStageCost(List<StageUsageDto> usage, String stage) {
        for (int i = usage.size() - 1; i >= 0; i--) {
            StageUsageDto u = usage.get(i);
            if (u.stage().equals(stage)) return props.cost(u.model(), u.inputTokens(), u.outputTokens());
        }
        return 0;
    }

    private static double round4(double d) {
        return Math.round(d * 10000) / 10000.0;
    }

    private static Map<String, Object> castTokens(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out.get("input") instanceof Number && out.get("output") instanceof Number ? out : Map.of();
    }

    /**
     * action 을 돌리다 생성 단계가 출력 한도에서 끊기면(StageOutputLimitException) 그 단계부터 이어 만든다. 예산은 단계 다시 만들기와 같다:
     * 작성·문항 글은 글 예산, 시작 코드·정답 쪽·관련 적은 자료는 테스트 예산, 설계도는 처음부터 예산. 예산이 없으면 그대로 던진다
     */
    private D withResume(TaskPipelineService.Run<D> run, java.util.function.Supplier<D> action, Consumer<String> onStep, int[] used, List<Map<String, Object>> log) {
        while (true) {
            try {
                return action.get();
            } catch (com.pair.domain.taskgen.exception.StageOutputLimitException e) {
                Stage stage = Stage.valueOf(e.stage());
                int kind = stage == Stage.BLUEPRINT ? 2 : stage == Stage.WRITING || stage == Stage.QUESTIONS ? 0 : 1;
                int max = kind == 0 ? MAX_TEXT_REGENS : kind == 1 ? MAX_TEST_REGENS : MAX_RESTARTS;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stage", e.stage());
                m.put("detail", clip(e.getMessage()));
                m.put("resumed", used[kind] < max);
                log.add(m);
                if (used[kind] >= max) throw e;
                used[kind]++;
                action = () -> run.resumeFrom(stage, onStep);
            }
        }
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 실행·계산 검사(+ run 이 있으면 단계 결과를 코드가 확인한 검사). 고친 Draft 는 run 의 단계 상태와 달라 run 검사는 건너뛴다 */
    private List<GenCheckResultDto> runChecks(TaskGenRequest form, D draft, TaskPipelineService.Run<D> run) {
        List<GenCheckResultDto> results = new java.util.ArrayList<>(runChecks(form, draft));
        if (run != null) results.addAll(run.checks());
        return results;
    }

    private List<GenCheckResultDto> runChecks(TaskGenRequest form, D draft) {
        return checks(form, draft).stream().map(c -> {
            try {
                return c.run(draft);
            } catch (RuntimeException e) {
                return new GenCheckResultDto(c.name(), false, "검사 중 오류: " + e.getMessage(), c.level());
            }
        }).toList();
    }
}
