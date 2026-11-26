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
    private final List<StageUsageDto> usage = new ArrayList<>();

    /** dev: DEV 직군이면 true, BIZ 면 false (직군마다 단계 모델이 다르다) */
    StageCallService(TaskGenAiService ai, TaskGenProperties props, boolean dev) {
        this.ai = ai;
        this.props = props;
        this.dev = dev;
    }

    <T> T call(TaskPipelineService.Stage stage, String system, String user, Class<T> type) {
        return call(stage, null, system, user, type);
    }

    /** label 이 있으면 기록 이름이 "REVIEW:LEAK" 처럼 된다(검수 점검별 토큰) */
    <T> T call(TaskPipelineService.Stage stage, String label, String system, String user, Class<T> type) {
        TaskGenProperties.Stage cfg = props.stage(dev, stage.name());
        long started = System.nanoTime();
        TaskGenAiService.Output<T> out = ai.generate(cfg.model(), system, user, type, cfg.maxTokens(), cfg.effort());
        usage.add(new StageUsageDto(label == null ? stage.name() : stage.name() + ":" + label, cfg.model(), cfg.effort(), out.inputTokens(), out.outputTokens(),
                Math.round((System.nanoTime() - started) / 1e9)));
        return out.value();
    }

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
        return List.copyOf(usage);
    }
}
