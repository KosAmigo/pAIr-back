package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.service.TaskGeneratorService.AttemptLog;
import com.pair.domain.taskgen.service.TaskGeneratorService.Extra;
import com.pair.domain.taskgen.service.TaskGeneratorService.Problem;
import com.pair.global.config.TaskGenProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * report.json 의 details 조립과 그에 쓰는 도우미. 키를 넣는 순서가 곧 report.json 의 키 순서라서 바꾸지 않는다.
 * 생성 흐름(TaskGeneratorService)은 값을 모아 {@link Input} 으로 넘기기만 한다.
 */
final class GenReportService {

    private static final int PROBLEM_CHARS = 200;

    private GenReportService() {}

    /**
     * report details 를 만드는 데 필요한 값 전부.
     * draftDetails: 하위 클래스가 더 남기는 내용(details(draft)). usage: 단계 호출 + 관문 호출 + 검사 안의 모델 호출을 합친 것.
     * runTally: 코드 실행 기록 {횟수, 대기ms, 실행ms}(없으면 null). runBlueprintRetries·runRedone: run 에서 얻는 값
     */
    record Input(TaskGenProperties props, boolean staged, Map<String, Object> draftDetails, Extra extra, List<StageUsageDto> usage,
            long[] runTally, List<GenCheckResultDto> results, int attempts, double cap, boolean capStopped,
            List<Map<String, Object>> costEstimates, List<Map<String, Object>> gateRounds, List<Map<String, Object>> regenerations,
            Map<String, Object> budget, Map<String, Object> warnLog, List<Map<String, Object>> outputLimitLog,
            List<List<String>> runBlueprintRetries, List<String> runRedone) {}

    static Map<String, Object> details(Input in) {
        TaskGenProperties props = in.props();
        Extra extra = in.extra();
        List<StageUsageDto> usage = in.usage();
        Map<String, Object> detail = new LinkedHashMap<>(in.draftDetails());
        detail.putAll(extra.details());
        detail.put("solo", soloOutcome(extra.checks())); // 난이도 참고 지표(AI 단독 풀이): missed·caught·skipped(이유)·error(이유)
        detail.put("timing", timing(usage, in.runTally()));
        Map<String, Object> soloTokens = extra.details().get("soloTokens") instanceof Map<?, ?> m ? castTokens(m) : Map.of();
        double soloCost = soloTokens.isEmpty() ? 0 : props.cost(props.soloModel() == null || props.soloModel().isBlank() ? "gpt-6-luna" : props.soloModel(),
                ((Number) soloTokens.get("input")).longValue(), ((Number) soloTokens.get("output")).longValue());
        if (in.staged()) { // 단계별 모델·토큰·시간(비용 확인용), 관문 결과, 다시 만든 이력
            detail.put("stages", usage);
            Map<String, Object> limit = new LinkedHashMap<>(); // 출력 한도 끊김: 추론 강도를 낮춰 받은 호출 수, 낮춰도 끊긴 단계 수, 이어 만든 기록
            limit.put("effortLowered", usage.stream().filter(u -> StageCallService.OUTPUT_LIMIT_LOWERED.equals(u.outputLimit())).count());
            limit.put("stageFailures", usage.stream().filter(u -> StageCallService.OUTPUT_LIMIT_FAILED.equals(u.outputLimit())).count());
            limit.put("resumes", in.outputLimitLog());
            detail.put("outputLimit", limit);
            JobCallService batchJob = JobCallService.current();
            if (batchJob != null && batchJob.window() != null) detail.put("batch", batchSummary(usage, batchJob.deadlineAt())); // 배치 작업: 배치로 받은 호출 수, 바로 호출로 바꾼 수와 이유
            detail.put("blueprintRetries", in.runBlueprintRetries()); // 설계도 형식 검사로 다시 받은 이유(점검: taskgen-runs/_verify/prompt-audit-2026-11-26.md)
            Map<String, Object> cost = new LinkedHashMap<>(); // 호출 비용 = 토큰 × 가격. byStage 는 단계(점검)별 합계
            Map<String, Double> byStageCost = new LinkedHashMap<>();
            for (StageUsageDto u : usage) byStageCost.merge(u.stage(), round4(StageCallService.cost(props, u)), Double::sum);
            if (soloCost > 0) byStageCost.put("SOLO", round4(soloCost));
            cost.put("totalUsd", round4(StageCallService.cost(props, usage) + soloCost));
            cost.put("capUsd", in.cap());
            cost.put("attempts", in.attempts());
            cost.put("stoppedByCap", in.capStopped());
            JobCallService saved = JobCallService.current();
            cost.put("reusedCalls", saved == null ? 0 : saved.reusedCount()); // 끊긴 작업을 이어 가며 저장된 결과를 다시 쓴 호출 수(그 비용은 위 합계에 한 번만 들어 있다)
            cost.put("estimates", in.costEstimates());
            cost.put("byStageUsd", byStageCost);
            detail.put("cost", cost);
            if (!in.runRedone().isEmpty()) detail.put("redoStages", in.runRedone());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("passed", in.results().stream().noneMatch(GenCheckResultDto::needsRegeneration));
            g.put("rounds", in.gateRounds());
            g.put("regenerations", in.regenerations());
            g.put("budget", in.budget());
            g.put("warnings", in.warnLog());
            detail.put("gates", g);
        }
        return detail;
    }

    /** 시도 1번의 기록: 통과하지 못한 검사(fail 과 warn)의 이름·등급·짧은 설명 */
    static AttemptLog attemptLog(int number, long startedNanos, List<GenCheckResultDto> results) {
        List<Problem> problems = results.stream().filter(c -> !c.ok())
                .map(c -> new Problem(c.name(), c.level(), c.detail() == null || c.detail().length() <= PROBLEM_CHARS ? c.detail()
                        : c.detail().substring(0, PROBLEM_CHARS) + "…"))
                .toList();
        boolean passed = results.stream().noneMatch(GenCheckResultDto::needsRegeneration);
        return new AttemptLog(number, passed, Math.round((System.nanoTime() - startedNanos) / 1e9), problems);
    }

    static String models(List<StageUsageDto> usage, String fallback) {
        String joined = usage.stream().map(StageUsageDto::model).distinct().collect(Collectors.joining(", "));
        return joined.isEmpty() ? fallback : joined;
    }

    /** 단계별 시간(초): 모델 호출(단계별 합과 호출 수)과 코드 실행(횟수, 대기, 실행). runTally = {횟수, 대기ms, 실행ms} */
    static Map<String, Object> timing(List<StageUsageDto> usage, long[] runTally) {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (StageUsageDto u : usage) byStage.merge(u.stage(), u.seconds(), Long::sum);
        t.put("modelCalls", usage.size());
        t.put("modelSeconds", usage.stream().mapToLong(StageUsageDto::seconds).sum());
        t.put("modelSecondsByStage", byStage);
        long[] r = runTally == null ? new long[3] : runTally;
        t.put("runCount", r[0]);
        t.put("runWaitSeconds", Math.round(r[1] / 100.0) / 10.0);
        t.put("runSeconds", Math.round(r[2] / 100.0) / 10.0);
        return t;
    }

    static List<Map<String, Object>> gateRecords(List<TaskGeneratorService.GateRun> runs) {
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

    /** 배치 작업의 요약(report details.batch): 배치로 받은 호출 수·평균 대기, 바로 호출로 바꾼 수와 이유(wait 대기 초과, deadline 마감, length 끊김, failed 실패) */
    static Map<String, Object> batchSummary(List<StageUsageDto> usage, java.time.Instant deadlineAt) {
        Map<String, Object> b = new LinkedHashMap<>();
        List<StageUsageDto> viaBatch = usage.stream().filter(u -> StageUsageDto.BATCH.equals(u.via()) && u.directReason().isEmpty()).toList();
        b.put("batchCalls", viaBatch.size());
        b.put("avgWaitSeconds", viaBatch.isEmpty() ? 0 : Math.round(viaBatch.stream().mapToLong(StageUsageDto::waitedMs).sum() / 1000.0 / viaBatch.size()));
        b.put("maxWaitSeconds", Math.round(viaBatch.stream().mapToLong(StageUsageDto::waitedMs).max().orElse(0) / 1000.0));
        Map<String, Long> direct = new LinkedHashMap<>();
        for (String r : List.of(BatchCallService.WAIT, BatchCallService.DEADLINE, BatchCallService.LENGTH, BatchCallService.FAILED)) {
            direct.put(r, usage.stream().filter(u -> StageUsageDto.DIRECT.equals(u.via()) && r.equals(u.directReason())).count());
        }
        b.put("directCalls", direct.values().stream().mapToLong(Long::longValue).sum());
        b.put("directByReason", direct);
        b.put("deadlineAt", deadlineAt == null ? null : deadlineAt.toString());
        return b;
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

    static double round4(double d) {
        return Math.round(d * 10000) / 10000.0;
    }

    static Map<String, Object> castTokens(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out.get("input") instanceof Number && out.get("output") instanceof Number ? out : Map.of();
    }
}
