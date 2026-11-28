package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 생성 작업 하나의 다시 만들기 예산과 비용 어림(생성 작업마다 새로 만든다).
 * 예산: 글 문제 2번 + 테스트·정답 문제 2번(종류별로 따로), 설계도부터 새로 1번(비용 상한 안에서만). 최대 5번.
 * 그래도 안 되면 실패 이유와 함께 검수 전(HIDDEN)으로 저장한다.
 */
final class RegenBudgetService {

    static final int MAX_TEXT_REGENS = 2; // 글 문제(답 노출·일관성 등: 작성·문항 글 단계)
    static final int MAX_TEST_REGENS = 2; // 테스트·정답 문제(실행 검사·정답 키·테스트 근거 등: 정답 쪽·시작 코드 단계)
    static final int MAX_RESTARTS = 1;

    /** 다음 다시 만들기 방법 */
    enum Redo {
        /** 실패를 낸 단계만 다시 */
        STAGES,
        /** 설계도부터 다시 */
        RESTART,
        /** 예산이 다했다: 더 만들지 않고 저장 */
        STOP
    }

    private final TaskGenProperties props;
    private final double cap;
    private int textRegens, testRegens, restarts, stageRegens;
    private final List<Map<String, Object>> costEstimates = new ArrayList<>(); // 다시 만들기 전 비용 어림과 근거
    private boolean capStopped;
    private String capDetail = "";

    /** cap: 직군·제한시간별 비용 상한($, report cost.capUsd 에 남는다) */
    RegenBudgetService(TaskGenProperties props, double cap) {
        this.props = props;
        this.cap = cap;
    }

    double cap() {
        return cap;
    }

    boolean capStopped() {
        return capStopped;
    }

    String capDetail() {
        return capDetail;
    }

    List<Map<String, Object>> costEstimates() {
        return costEstimates;
    }

    // ── 다시 만들기 방법 정하기 ─────────────────────────────

    /**
     * 실패를 낸 단계만(종류별 예산 안에서), 설계도 문제거나 예산이 다했으면 설계도부터(1번), 그것도 다했으면 멈춤.
     * natural: 실패가 설계도 문제를 가리킴. kinds: 실패의 종류(text·test)
     */
    Redo decide(boolean staged, boolean natural, Set<String> kinds) {
        boolean textLeft = textRegens < MAX_TEXT_REGENS, testLeft = testRegens < MAX_TEST_REGENS;
        boolean byStage = staged && !natural && kinds.stream().allMatch(k -> k.equals("text") ? textLeft : testLeft);
        if (byStage) return Redo.STAGES;
        return restarts >= MAX_RESTARTS ? Redo.STOP : Redo.RESTART;
    }

    /** 정한 방법으로 다시 만들기로 했으니 예산에서 뺀다 */
    void use(Redo redo, Set<String> kinds) {
        if (redo == Redo.STAGES) {
            stageRegens++;
            if (kinds.contains("text")) textRegens++;
            if (kinds.contains("test")) testRegens++;
        } else restarts++;
    }

    // ── 출력 한도 끊김으로 단계부터 이어 만들기(같은 예산을 쓴다) ─────────────────────────────

    /** 작성·문항 글은 글 예산, 시작 코드·정답 쪽·관련 적은 자료는 테스트 예산, 설계도는 처음부터 예산 */
    boolean canResume(Stage stage) {
        return switch (kindOf(stage)) {
            case 0 -> textRegens < MAX_TEXT_REGENS;
            case 1 -> testRegens < MAX_TEST_REGENS;
            default -> restarts < MAX_RESTARTS;
        };
    }

    /** 이어 만들기로 했으니 해당 예산에서 뺀다(단계 다시 만들기 횟수에는 넣지 않는다) */
    void useResume(Stage stage) {
        switch (kindOf(stage)) {
            case 0 -> textRegens++;
            case 1 -> testRegens++;
            default -> restarts++;
        }
    }

    private static int kindOf(Stage stage) {
        return stage == Stage.BLUEPRINT ? 2 : stage == Stage.WRITING || stage == Stage.QUESTIONS ? 0 : 1;
    }

    // ── 비용 어림과 상한 ─────────────────────────────

    /**
     * 다음 다시 만들기 비용 = 다시 돌 단계들이 바로 앞 시도에서 쓴 비용 + 검사 비용(다시 돌 단계를 모르면 처음부터 만든 비용).
     * 어림 근거를 costEstimates 에 남기고, 지금까지 쓴 비용과 합쳐 상한을 넘으면 true(capDetail 에 이유를 적는다)
     */
    boolean overCap(int attempt, List<String> planned, List<StageUsageDto> usage, double createCost, double checkCost, double spent,
            List<GenCheckResultDto> failed) {
        Map<String, Double> stageUsd = new LinkedHashMap<>();
        for (String st : planned) stageUsd.put(st, lastStageCost(usage, st));
        double regenCost = planned.isEmpty() ? createCost : stageUsd.values().stream().mapToDouble(Double::doubleValue).sum();
        double estimate = regenCost + checkCost;
        Map<String, Object> est = new LinkedHashMap<>(); // report.json 에 남기는 어림 근거
        est.put("attempt", attempt);
        est.put("method", planned.isEmpty() ? "처음부터 만든 비용 + 검사 비용" : "다시 돌 단계가 바로 앞 시도에서 쓴 비용 + 검사 비용");
        est.put("stages", planned);
        est.put("stageUsd", stageUsd.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> GenReportService.round4(e.getValue()), (a, b) -> a, LinkedHashMap::new)));
        est.put("regenUsd", GenReportService.round4(regenCost));
        est.put("checkUsd", GenReportService.round4(checkCost));
        est.put("spentUsd", GenReportService.round4(spent));
        est.put("estimateUsd", GenReportService.round4(estimate));
        costEstimates.add(est);
        if (spent + estimate <= cap) return false;
        capStopped = true;
        capDetail = String.format(java.util.Locale.ROOT, "지금까지 $%.2f, 다음 다시 만들기 예상 $%.2f → 상한 $%.2f 초과. 마지막 실패: %s", spent, estimate, cap,
                failed.stream().map(c -> c.name() + ": " + TaskGeneratorService.clip(c.detail())).collect(Collectors.joining(" / ")));
        return true;
    }

    /** stage 를 마지막으로 부른 호출 하나의 비용($). 부른 적 없으면 0 */
    private double lastStageCost(List<StageUsageDto> usage, String stage) {
        for (int i = usage.size() - 1; i >= 0; i--) {
            StageUsageDto u = usage.get(i);
            if (u.stage().equals(stage)) return StageCallService.cost(props, u);
        }
        return 0;
    }

    // ── report ─────────────────────────────

    /** report gates.budget: 종류별로 쓴 다시 만들기(글 문제 text, 테스트·정답 문제 test, 설계도부터 restarts) */
    Map<String, Object> budgetRecord() {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("textRegens", textRegens);
        budget.put("maxTextRegens", MAX_TEXT_REGENS);
        budget.put("testRegens", testRegens);
        budget.put("maxTestRegens", MAX_TEST_REGENS);
        budget.put("stageRegens", stageRegens);
        budget.put("restarts", restarts);
        budget.put("maxRestarts", MAX_RESTARTS);
        return budget;
    }
}
