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

    public static final String STEP_GENERATING = "생성 중";
    public static final String STEP_CHECKING = "확인 중";
    public static final String STEP_REGENERATING = "다시 생성 중";
    public static final String STEP_SAVING = "저장 중";

    /** 생성 결과. passed 는 모든 검사 통과 여부 */
    public record Outcome(String taskCode, boolean passed, int attempts, List<GenCheckResultDto> checks) {}

    /** _gen/report.json 내용 (설계 7장) */
    public record GenReport(String model, int attempts, long seconds, long inputTokens, long outputTokens,
            boolean passed, TaskGenRequest form, List<GenCheckResultDto> checks, String retryError,
            java.util.Map<String, Object> details) {}

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

    /** Draft 에서 모델이 정한 역량(검증 전 값) */
    protected abstract List<String> draftCompetencies(D draft);

    /** 한 트랜잭션으로 저장하고 과제 code 를 돌려준다 */
    protected abstract String save(TaskGenRequest form, D draft, GenReport report, long adminId);

    public final Outcome generate(TaskGenRequest form, long adminId, Consumer<String> onStep) {
        long started = System.nanoTime();
        String model = props.model();
        String system = system(form);

        onStep.accept(STEP_GENERATING);
        TaskGenAiService.Output<D> first = ai.generate(model, system, user(form, List.of()), draftType());
        long in = first.inputTokens(), out = first.outputTokens();
        D draft = first.value();
        int attempts = 1;
        String retryError = null;

        onStep.accept(STEP_CHECKING);
        List<GenCheckResultDto> results = runChecks(form, draft);
        List<GenCheckResultDto> failed = results.stream().filter(GenCheckResultDto::needsRegeneration).toList();

        if (!failed.isEmpty()) {
            onStep.accept(STEP_REGENERATING);
            try {
                TaskGenAiService.Output<D> second = ai.generate(model, system, user(form, failed), draftType());
                in += second.inputTokens();
                out += second.outputTokens();
                attempts = 2;
                draft = second.value();
                onStep.accept(STEP_CHECKING);
                results = runChecks(form, draft);
            } catch (RuntimeException e) {
                // 다시 만들기가 실패해도 이미 만든 첫 결과는 버리지 않고 실패 표시와 함께 저장한다
                retryError = String.valueOf(e.getMessage());
            }
        }

        // warn 검사는 통과 여부에 영향을 주지 않는다(검수자에게 알리기만 한다)
        boolean passed = results.stream().noneMatch(GenCheckResultDto::needsRegeneration);
        onStep.accept(STEP_SAVING);
        long seconds = Math.round((System.nanoTime() - started) / 1e9);
        // 요청에 역량이 있으면 그대로, 비어 있으면 모델이 정한 값(검증해서 잘못된 key 는 버림)을 쓴다. 보고서 form 에도 이 값이 남는다
        TaskGenRequest saved = form.competencies() != null && !form.competencies().isEmpty() ? form
                : form.withCompetencies(TaskGenRequest.pickCompetencies(draftCompetencies(draft)));
        String code = save(saved, draft, new GenReport(model, attempts, seconds, in, out, passed, saved, results, retryError, details(draft)), adminId);
        return new Outcome(code, passed, attempts, results);
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
