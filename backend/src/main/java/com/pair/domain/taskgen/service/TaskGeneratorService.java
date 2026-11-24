package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.List;
import java.util.function.Consumer;

/**
 * 과제 생성 순서(Template Method): 프롬프트 → AI 호출 → 검사 → (실패하면 실패 내용을 붙여 1번만 다시 생성) → 저장.
 * 하위 클래스는 프롬프트, 출력 형식, 검사 목록, 저장 방법만 정한다.
 * AI 호출이 실패하면(첫 호출) 예외를 그대로 던지고 저장하지 않는다.
 * 검사가 끝내 실패해도 저장한다(검수 전 상태라 어디에도 보이지 않는다).
 * 검사 등급: fail 만 다시 생성을 일으키고, warn 은 저장한 뒤 검수자에게 알리기만 한다.
 */
public abstract class TaskGeneratorService<D> {

    /**
     * 생성 프롬프트의 맨 앞에 붙는 핵심 정의. 기준 문서(pAIr-docs 설계/좋은_과제_기준.md) 1장, M3, M5, Q3.
     * 프롬프트에서 '%' 를 쓰지 않는다(SYSTEM.formatted 로 합쳐진다).
     */
    static final String PURPOSE = """
            [과제의 목적] AI 를 쓰면 빨라지지만, AI 답을 그대로 받아들이면 함정에 걸려서, 문제를 나누고 AI 결과를 검증하고 판단하는 사람이 더 높은 점수를 받는 과제를 만듭니다. \
            응시자가 과제 설명과 자료를 AI 에 그대로 붙여 넣고 나온 답을 손대지 않고 내면 함정에 걸려야 합니다. \
            그렇다고 AI 를 써도 못 풀 만큼 꼬아서 어렵게 만들면 안 됩니다. 꼼꼼히 확인하면 함정을 피할 수 있어야 합니다.
            """;

    /** 명세와 답 노출의 경계. 기준 문서 2장(M1, M4). 양쪽 생성기가 같은 문장을 쓴다 */
    static final String BOUNDARY = """
            [명세와 답 노출의 경계] 줘도 되는 것은 무엇이 맞는 결과인지입니다(입력과 기대 출력의 예, 항상 성립해야 하는 조건, 현상과 재현 값, 측정 범위나 집계 정의 같은 사실). \
            주면 안 되는 것은 왜 틀렸는지와 어디를 어떻게 고칠지입니다(원인, 버그 위치, 고치는 방법, 계산 순서나 절차, "~해야 한다", "~해서는 안 된다", "~만으로 판단하지 않는다" 같은 지시). \
            함정을 피하는 데 꼭 필요한 기대 결과(예: 끝나는 시각에 바로 시작하는 예약)는 여러 예 가운데 하나로 두고, 따로 떼어 규칙 문장으로 강조하지 않습니다.
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

    protected TaskGeneratorService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
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
     * 여기서 만든 fail 검사도 다시 생성을 일으킨다(비용이 큰 검사를 다른 검사가 통과한 뒤에만 돌릴 때 쓴다)
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

    public final Outcome generate(TaskGenRequest form, long adminId, Consumer<String> onStep) {
        long started = System.nanoTime();
        String model = props.model();
        String system = system(form);

        List<AttemptLog> attemptLog = new java.util.ArrayList<>();
        long attemptStarted = System.nanoTime();
        onStep.accept(STEP_GENERATING);
        TaskGenAiService.Output<D> first = ai.generate(model, system, user(form, List.of()), draftType());
        long in = first.inputTokens(), out = first.outputTokens();
        D draft = first.value();
        int attempts = 1;
        String retryError = null;

        onStep.accept(STEP_CHECKING);
        Checked checked = runAll(form, draft);
        List<GenCheckResultDto> results = checked.results();
        Extra extra = checked.extra();
        attemptLog.add(attemptLog(1, attemptStarted, results));
        List<GenCheckResultDto> failed = results.stream().filter(GenCheckResultDto::needsRegeneration).toList();

        if (!failed.isEmpty()) {
            onStep.accept(STEP_REGENERATING);
            attemptStarted = System.nanoTime();
            try {
                TaskGenAiService.Output<D> second = ai.generate(model, system, user(form, failed), draftType());
                in += second.inputTokens();
                out += second.outputTokens();
                attempts = 2;
                draft = second.value();
                onStep.accept(STEP_CHECKING);
                checked = runAll(form, draft);
                results = checked.results();
                extra = checked.extra();
                attemptLog.add(attemptLog(2, attemptStarted, results));
            } catch (RuntimeException e) {
                // 다시 만들기가 실패해도 이미 만든 첫 결과는 버리지 않고 실패 표시와 함께 저장한다
                retryError = String.valueOf(e.getMessage());
                attemptLog.add(new AttemptLog(2, false, Math.round((System.nanoTime() - attemptStarted) / 1e9),
                        List.of(new Problem("다시 생성 실패", GenCheckResultDto.FAIL, String.valueOf(e.getMessage())))));
            }
        }

        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>(details(draft));
        detail.putAll(extra.details());

        // warn 검사는 통과 여부에 영향을 주지 않는다(검수자에게 알리기만 한다)
        boolean passed = results.stream().noneMatch(GenCheckResultDto::needsRegeneration);
        onStep.accept(STEP_SAVING);
        long seconds = Math.round((System.nanoTime() - started) / 1e9);
        // 요청에 역량이 있으면 그대로, 비어 있으면 모델이 정한 값(검증해서 잘못된 key 는 버림)을 쓴다. 보고서 form 에도 이 값이 남는다
        TaskGenRequest saved = form.competencies() != null && !form.competencies().isEmpty() ? form
                : form.withCompetencies(TaskGenRequest.pickCompetencies(draftCompetencies(draft)));
        String code = save(saved, draft, new GenReport(model, attempts, seconds, in, out, passed, saved, results, retryError, detail, attemptLog), adminId, extra.files());
        return new Outcome(code, passed, attempts, results);
    }

    private record Checked(List<GenCheckResultDto> results, Extra extra) {}

    /** 기본 검사 + 시도마다 도는 후속 검사(afterChecks). 후속 검사 결과도 results 에 들어간다 */
    private Checked runAll(TaskGenRequest form, D draft) {
        List<GenCheckResultDto> results = runChecks(form, draft);
        Extra extra = afterChecks(form, draft, results);
        if (!extra.checks().isEmpty()) {
            List<GenCheckResultDto> all = new java.util.ArrayList<>(results);
            all.addAll(extra.checks());
            results = all;
        }
        return new Checked(results, extra);
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
