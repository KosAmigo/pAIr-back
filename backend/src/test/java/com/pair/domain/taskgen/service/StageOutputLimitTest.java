package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.exception.StageOutputLimitException;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.OutputLimitException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 생성 단계가 추론 강도를 낮춰도 출력 한도에서 끊기면 과제 전체 오류가 아니라 단계 실패: 그 단계부터 이어 만든다(g5 08 WRITING) */
class StageOutputLimitTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final TaskGenRequest FORM = new TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20);

    /** 작성 단계를 cuts 번 끊는 가짜 AI(그다음부터는 정한 값) */
    static final class CutWriting extends TaskPipelineServiceTest.StageAi {
        int cuts;
        final List<Class<?>> calls = new ArrayList<>();

        CutWriting(int cuts) {
            this.cuts = cuts;
            add(BlueprintDto.class, TaskPipelineServiceTest.devBlueprint()).add(DevWritingDto.class, TaskPipelineServiceTest.devWriting())
                    .add(DevCodeDto.class, TaskPipelineServiceTest.devCode()).add(DevAnswerDto.class, TaskPipelineServiceTest.devAnswer());
        }

        @Override
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
            calls.add(type);
            if (type == DevWritingDto.class && cuts > 0) {
                cuts--;
                throw new OutputLimitException("OpenAI 응답이 비어 있습니다 (finish=length)", 2000, 16000, true);
            }
            return super.generate(model, system, user, type, maxTokens, effort);
        }
    }

    @Test
    @DisplayName("작성 단계가 끊기면 단계 이름을 담은 StageOutputLimitException, 버린 토큰은 failed 로 기록, 이어 만들기는 작성부터(설계도는 다시 안 부름)")
    void resumesFromCutStage() {
        var ai = new CutWriting(1);
        var run = new DevPipelineService(ai, PROPS).start(FORM);
        assertThatThrownBy(() -> run.create(s -> {})).isInstanceOfSatisfying(StageOutputLimitException.class, e -> {
            assertThat(e.stage()).isEqualTo("WRITING");
            assertThat(e.getMessage()).contains("[단계 WRITING");
        });
        assertThat(run.usage()).filteredOn(u -> StageCallService.OUTPUT_LIMIT_FAILED.equals(u.outputLimit())).singleElement()
                .satisfies(u -> assertThat(u.outputTokens()).isEqualTo(16000));

        ai.calls.clear();
        var draft = run.resumeFrom(TaskPipelineService.Stage.WRITING, s -> {});
        assertThat(draft.title()).isEqualTo("누적 할인을 반영한 결제액");
        assertThat(ai.calls).containsExactly(DevWritingDto.class, DevCodeDto.class, DevAnswerDto.class);
        assertThat(run.redone()).containsExactly("WRITING", "CODE", "ANSWER");
    }

    @Test
    @DisplayName("관문(REVIEW) 단계의 끊김은 단계 실패로 넘기지 않는다(예전처럼 AiUnavailableException)")
    void reviewStageKeepsOldError() {
        var calls = new StageCallService(new TaskPipelineServiceTest.StageAi() {
            @Override
            public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
                throw new OutputLimitException("finish=length", 1, 2, true);
            }
        }, PROPS, true);
        assertThatThrownBy(() -> calls.call(TaskPipelineService.Stage.REVIEW, "LEAK", "s", "u", String.class))
                .isInstanceOf(com.pair.infra.ai.AiUnavailableException.class).isNotInstanceOf(StageOutputLimitException.class);
        var all = java.util.EnumSet.of(TaskPipelineService.Stage.BLUEPRINT, TaskPipelineService.Stage.WRITING, TaskPipelineService.Stage.CODE, TaskPipelineService.Stage.ANSWER);
        assertThat(StageRouteService.from(TaskPipelineService.Stage.CODE, all).stages()).containsExactly(TaskPipelineService.Stage.CODE, TaskPipelineService.Stage.ANSWER);
    }
}
