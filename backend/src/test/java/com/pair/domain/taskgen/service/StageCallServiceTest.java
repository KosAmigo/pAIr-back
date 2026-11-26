package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StageCallServiceTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);

    private static List<String> modelsFor(boolean dev, Stage... stages) {
        var ai = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, new ReviewResultDto(List.of()));
        var calls = new StageCallService(ai, PROPS, dev);
        for (Stage s : stages) calls.call(s, "s", "u", ReviewResultDto.class);
        return ai.models;
    }

    @Test
    @DisplayName("직군마다 단계 모델 설정을 따른다: 기본은 DEV·BIZ 모두 sol 이 생성하고 Sonnet 이 관문, 답 노출 2차는 terra")
    void routesModelsPerTrack() {
        assertThat(modelsFor(true, Stage.BLUEPRINT, Stage.WRITING, Stage.CODE, Stage.ANSWER, Stage.REVIEW, Stage.REVIEW2))
                .containsExactly("gpt-6.1-sol", "gpt-6.1-sol", "gpt-6.1-sol", "gpt-6.1-sol", "gpt-6.1-sol", "claude-sonnet-5-5");
        assertThat(modelsFor(false, Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER, Stage.REVIEW, Stage.REVIEW2))
                .containsExactly("gpt-6.1-sol", "gpt-6.1-sol", "gpt-6.1-sol", "gpt-6.1-sol", "claude-sonnet-5-5", "gpt-6.1-terra");
    }

    @Test
    @DisplayName("비용은 호출마다 토큰×가격(sol $2/$10)을 더한다")
    void sumsCost() {
        var ai = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, new ReviewResultDto(List.of()));
        var calls = new StageCallService(ai, PROPS, false);
        calls.call(Stage.BLUEPRINT, "s", "u", ReviewResultDto.class); // sol, 100 입력 / 200 출력 토큰
        assertThat(calls.cost()).isCloseTo((100 * 2.0 + 200 * 10.0) / 1_000_000, org.assertj.core.data.Offset.offset(1e-12));
    }
}
