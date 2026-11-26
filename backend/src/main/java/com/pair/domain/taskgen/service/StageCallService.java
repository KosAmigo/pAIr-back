package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

/** 단계 호출 1번: 단계별 모델·추론 강도·출력 한도로 AI 를 부르고 토큰과 시간을 기록한다. Run 하나가 하나씩 쥔다 */
final class StageCallService {

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    private final boolean dev;
    private final JobCallService saved = JobCallService.current(); // 작업이 이어 가기를 켰으면(작업 스레드에 묶여 있음) 호출을 저장·재사용한다
    private final List<StageUsageDto> usage = java.util.Collections.synchronizedList(new ArrayList<>());

    /** dev: DEV 직군이면 true, BIZ 면 false (직군마다 단계 모델이 다르다) */
    StageCallService(TaskGenAiService ai, TaskGenProperties props, boolean dev) {
        this.ai = ai;
        this.props = props;
        this.dev = dev;
    }

    <T> T call(TaskPipelineService.Stage stage, String system, String user, Class<T> type) {
        return call(stage, null, system, user, type);
    }

    /** 같은 단계를 입력마다 따로 동시에 부른다(결과 순서는 입력 순서). 한 호출이라도 실패하면 그 오류를 그대로 던진다 */
    <T> List<T> callParallel(TaskPipelineService.Stage stage, String system, List<String> users, Class<T> type) {
        if (users.size() <= 1) return users.stream().map(u -> call(stage, system, u, type)).toList();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<T>> fs = users.stream().map(u -> pool.submit(() -> call(stage, system, u, type))).toList();
            List<T> out = new ArrayList<>();
            for (var f : fs) {
                try {
                    out.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
    }

    /** label 이 있으면 기록 이름이 "REVIEW:LEAK" 처럼 된다(검수 점검별 토큰) */
    <T> T call(TaskPipelineService.Stage stage, String label, String system, String user, Class<T> type) {
        TaskGenProperties.Stage cfg = props.stage(dev, stage.name());
        long started = System.nanoTime();
        TaskGenAiService.Output<T> out;
        String name = label == null ? stage.name() : stage.name() + ":" + label;
        String hash = null;
        int nth = 0;
        if (saved != null) { // 이어 가기: 같은 입력의 nth 번째 호출이 저장돼 있으면 모델을 부르지 않는다
            hash = JobCallService.hash(cfg.model(), cfg.effort(), cfg.maxTokens(), system, user, type.getName());
            nth = saved.nextNth(hash);
            var stored = saved.find(hash, nth);
            if (stored.isPresent()) {
                var value = saved.read(stored.get(), type);
                if (value.isPresent()) {
                    var c = stored.get();
                    usage.add(new StageUsageDto(name, cfg.model(), cfg.effort(), c.getInputTokens(), c.getOutputTokens(), 0));
                    saved.addCost(props.cost(cfg.model(), c.getInputTokens(), c.getOutputTokens()));
                    saved.markReused();
                    return value.get();
                }
            }
        }
        try {
            out = ai.generate(cfg.model(), system, user, type, cfg.maxTokens(), cfg.effort());
        } catch (com.pair.infra.ai.OutputLimitException e) {
            // 추론 강도를 낮춰도 출력 한도에서 끊김: 버린 토큰도 비용으로 남기고, 생성 단계면 단계 실패로 넘겨 그 단계부터 다시 만들게 한다(관문 단계는 예전처럼)
            usage.add(new StageUsageDto(name, cfg.model(), cfg.effort(), e.inputTokens(), e.outputTokens(), Math.round((System.nanoTime() - started) / 1e9), OUTPUT_LIMIT_FAILED));
            String msg = "[단계 " + name + ", " + cfg.model() + ", 출력 한도 " + cfg.maxTokens() + "] " + e.getMessage();
            if (GENERATION.contains(stage)) throw new com.pair.domain.taskgen.exception.StageOutputLimitException(stage.name(), msg, e);
            throw new com.pair.infra.ai.AiUnavailableException(msg);
        } catch (com.pair.infra.ai.AiUnavailableException e) {
            // 어느 단계에서 났는지 남긴다(예: 응답이 한도에서 끊김)
            throw new com.pair.infra.ai.AiUnavailableException("[단계 " + (label == null ? stage.name() : stage.name() + ":" + label) + ", " + cfg.model() + "] " + e.getMessage());
        } catch (IllegalStateException e) {
            // 출력 한도 끊김(finish=length)도 어느 단계인지 남긴다(g4 02: 단계를 몰랐음)
            if (e.getMessage() == null || !e.getMessage().contains("finish=length")) throw e;
            throw new IllegalStateException("[단계 " + (label == null ? stage.name() : stage.name() + ":" + label) + ", " + cfg.model() + ", 출력 한도 " + cfg.maxTokens() + "] " + e.getMessage(), e);
        }
        usage.add(new StageUsageDto(name, cfg.model(), cfg.effort(), out.inputTokens(), out.outputTokens(),
                Math.round((System.nanoTime() - started) / 1e9), out.effortLowered() ? OUTPUT_LIMIT_LOWERED : ""));
        if (saved != null) {
            saved.addCost(props.cost(cfg.model(), out.inputTokens(), out.outputTokens()));
            saved.save(hash, nth, stage.name(), label, cfg.model(), out.value(), out.inputTokens(), out.outputTokens());
        }
        return out.value();
    }

    /** StageUsageDto.outputLimit 값: 추론 강도를 낮춰 다시 불러 받음 / 낮춰도 끊겨 단계 실패 */
    static final String OUTPUT_LIMIT_LOWERED = "lowered";
    static final String OUTPUT_LIMIT_FAILED = "failed";

    /** 출력 한도 끊김을 단계 실패로 넘기는 생성 단계(관문·점검 단계는 넘기지 않는다) */
    private static final java.util.Set<TaskPipelineService.Stage> GENERATION = java.util.EnumSet.of(TaskPipelineService.Stage.BLUEPRINT, TaskPipelineService.Stage.WRITING,
            TaskPipelineService.Stage.CODE, TaskPipelineService.Stage.QUESTIONS, TaskPipelineService.Stage.ANSWER, TaskPipelineService.Stage.FILLER);

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 단계 입력에 붙이는 JSON 글 */
    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지금까지 부른 호출의 비용($) */
    double cost() {
        return cost(props, usage);
    }

    static double cost(TaskGenProperties props, List<StageUsageDto> usage) {
        return usage.stream().mapToDouble(u -> props.cost(u.model(), u.inputTokens(), u.outputTokens())).sum();
    }

    void record(StageUsageDto u) {
        usage.add(u);
    }

    List<StageUsageDto> usage() {
        synchronized (usage) {
            return List.copyOf(usage);
        }
    }
}
