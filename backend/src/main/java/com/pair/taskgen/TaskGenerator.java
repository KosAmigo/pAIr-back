package com.pair.taskgen;

import java.util.List;
import java.util.function.Consumer;

/**
 * 과제 생성 순서(Template Method): 프롬프트 → AI 호출 → 검사 → (실패하면 실패 내용을 붙여 1번만 다시 생성) → 저장.
 * 하위 클래스는 프롬프트, 출력 형식, 검사 목록, 저장 방법만 정한다.
 * AI 호출이 실패하면(첫 호출) 예외를 그대로 던지고 저장하지 않는다.
 * 검사가 끝내 실패해도 저장한다(검수 전 상태라 어디에도 보이지 않는다).
 */
public abstract class TaskGenerator<D> {

    public static final String STEP_GENERATING = "생성 중";
    public static final String STEP_CHECKING = "확인 중";
    public static final String STEP_REGENERATING = "다시 생성 중";
    public static final String STEP_SAVING = "저장 중";

    /** 생성 결과. passed 는 모든 검사 통과 여부 */
    public record Outcome(String taskCode, boolean passed, int attempts, List<CheckResult> checks) {}

    /** _gen/report.json 내용 (설계 7장) */
    public record GenReport(String model, int attempts, long seconds, long inputTokens, long outputTokens,
            boolean passed, TaskGenForm form, List<CheckResult> checks, String retryError,
            java.util.Map<String, Object> details) {}

    private final TaskGenAi ai;
    private final TaskGenProperties props;

    protected TaskGenerator(TaskGenAi ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    protected abstract String system(TaskGenForm form);

    /** failed: 앞선 시도에서 실패한 검사들. 첫 시도는 빈 목록 */
    protected abstract String user(TaskGenForm form, List<CheckResult> failed);

    protected abstract Class<D> draftType();

    /** 이 Draft 에 대한 검사 목록 (실행 결과를 검사끼리 나눠 쓰도록 Draft 마다 새로 만든다) */
    protected abstract List<GenCheck<D>> checks(D draft);

    /** report.json 에 더 남길 내용 (예: 비개발 함정 정보) */
    protected java.util.Map<String, Object> details(D draft) {
        return java.util.Map.of();
    }

    /** 한 트랜잭션으로 저장하고 과제 code 를 돌려준다 */
    protected abstract String save(TaskGenForm form, D draft, GenReport report, long adminId);

    public final Outcome generate(TaskGenForm form, long adminId, Consumer<String> onStep) {
        long started = System.nanoTime();
        String model = props.model();
        String system = system(form);

        onStep.accept(STEP_GENERATING);
        TaskGenAi.Output<D> first = ai.generate(model, system, user(form, List.of()), draftType());
        long in = first.inputTokens(), out = first.outputTokens();
        D draft = first.value();
        int attempts = 1;
        String retryError = null;

        onStep.accept(STEP_CHECKING);
        List<CheckResult> results = runChecks(draft);
        List<CheckResult> failed = results.stream().filter(c -> !c.ok()).toList();

        if (!failed.isEmpty()) {
            onStep.accept(STEP_REGENERATING);
            try {
                TaskGenAi.Output<D> second = ai.generate(model, system, user(form, failed), draftType());
                in += second.inputTokens();
                out += second.outputTokens();
                attempts = 2;
                draft = second.value();
                onStep.accept(STEP_CHECKING);
                results = runChecks(draft);
            } catch (RuntimeException e) {
                // 다시 만들기가 실패해도 이미 만든 첫 결과는 버리지 않고 실패 표시와 함께 저장한다
                retryError = String.valueOf(e.getMessage());
            }
        }

        boolean passed = results.stream().allMatch(CheckResult::ok);
        onStep.accept(STEP_SAVING);
        long seconds = Math.round((System.nanoTime() - started) / 1e9);
        String code = save(form, draft, new GenReport(model, attempts, seconds, in, out, passed, form, results, retryError, details(draft)), adminId);
        return new Outcome(code, passed, attempts, results);
    }

    private List<CheckResult> runChecks(D draft) {
        return checks(draft).stream().map(c -> {
            try {
                return c.run(draft);
            } catch (RuntimeException e) {
                return new CheckResult(c.name(), false, "검사 중 오류: " + e.getMessage());
            }
        }).toList();
    }
}
