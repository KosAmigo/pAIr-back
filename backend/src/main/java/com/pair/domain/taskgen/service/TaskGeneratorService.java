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
            결함을 피하는 데 꼭 필요한 기대 결과(예: 끝나는 시각에 바로 시작하는 예약)는 여러 예 가운데 하나로 두고, 따로 떼어 규칙 문장으로 강조하지 않습니다. 규칙 문장("~하면 예외가 발생한다"), 원인 문장, "~하지 않는다"·"~로 잡히지 않는다" 꼴, 값을 합치는 방법(차이, 비율, 가중치)은 쓰지 않습니다. 정의는 무엇을 세는지(대상과 범위)까지만 쓰고, 두 대상이 서로 다르다고 직접 짚는 문장("A와 B는 다르다")과 "원인:" 표지도 쓰지 않습니다.
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
        this.leakJudge = new AnswerLeakJudgeService(ai, props);
    }

    /** M5 답 노출 AI 판정 */
    protected final AnswerLeakJudgeService leakJudge;

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
