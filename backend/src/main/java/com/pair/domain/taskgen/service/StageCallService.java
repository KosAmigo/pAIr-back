package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

/** 단계 호출 1번: 단계별 모델·추론 강도·출력 한도로 AI 를 부르고 토큰과 시간을 기록한다. Run 하나가 하나씩 쥔다 */
final class StageCallService {

    private final TaskGenAiService ai;
    private final TaskGenProperties props;
    private final List<StageUsageDto> usage = new ArrayList<>();

    StageCallService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    <T> T call(TaskPipelineService.Stage stage, String system, String user, Class<T> type) {
        TaskGenProperties.Stage cfg = props.stage(stage.name());
        long started = System.nanoTime();
        TaskGenAiService.Output<T> out = ai.generate(cfg.model(), system, user, type, cfg.maxTokens(), cfg.effort());
        usage.add(new StageUsageDto(stage.name(), cfg.model(), cfg.effort(), out.inputTokens(), out.outputTokens(),
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

    List<StageUsageDto> usage() {
        return List.copyOf(usage);
    }
}
