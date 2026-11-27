package com.pair.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 출력 한도 끊김(finish=length, 빈 답): 추론 강도를 한 단계 낮춰 한 번만 다시 부르고, 버린 토큰도 센다(g5 08 WRITING) */
class ModelClientOutputLimitTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, "medium", 48000, null, null, null, null, null, null, null, null, null, null, null, "high");

    /** cutAt 의 추론 강도로 부르면 출력 한도에서 끊긴다(토큰 100/16000) */
    static final class Cutting implements ModelClient.Backend {
        final Set<String> cutAt;
        final List<String> efforts = new ArrayList<>();

        Cutting(Set<String> cutAt) {
            this.cutAt = cutAt;
        }

        @Override
        public <T> ModelClient.Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort) {
            efforts.add(effort);
            if (cutAt.contains(effort)) throw new OutputLimitException("OpenAI 응답이 비어 있습니다 (finish=length)", 100, 16000, false);
            return new ModelClient.Result<>(null, 7, 9, 3);
        }

        @Override
        public ModelClient.TextResult text(String model, String system, String user, int maxTokens, String effort) {
            return new ModelClient.TextResult("답", 1, 1, 0);
        }
    }

    @Test
    @DisplayName("medium 이 끊기면 low 로 한 번 더 부르고, 토큰은 두 호출의 합이며 낮췄다고 표시한다")
    void lowersEffortOnce() {
        var b = new Cutting(Set.of("medium"));
        var r = new ModelClient(PROPS, b, b).json("gpt-6.1-sol", "s", "u", String.class, 16000, "medium");
        assertThat(b.efforts).containsExactly("medium", "low");
        assertThat(r.effortLowered()).isTrue();
        assertThat(r.inputTokens()).isEqualTo(107);
        assertThat(r.outputTokens()).isEqualTo(16009);
        assertThat(new ModelClient(PROPS, b, b).generate("gpt-6.1-sol", "s", "u", String.class, 16000, "medium").effortLowered()).isTrue();
    }

    @Test
    @DisplayName("낮춰도 끊기면 두 호출의 토큰을 담은 OutputLimitException, low 는 더 낮추지 않는다, 다른 오류는 다시 부르지 않는다")
    void givesUpAfterOneRetry() {
        var b = new Cutting(Set.of("medium", "low"));
        assertThatThrownBy(() -> new ModelClient(PROPS, b, b).json("gpt-6.1-sol", "s", "u", String.class, 16000, "medium"))
                .isInstanceOfSatisfying(OutputLimitException.class, e -> {
                    assertThat(e.effortLowered()).isTrue();
                    assertThat(e.outputTokens()).isEqualTo(32000);
                    assertThat(e.getMessage()).contains("medium→low");
                });
        assertThat(b.efforts).containsExactly("medium", "low");

        var low = new Cutting(Set.of("low"));
        assertThatThrownBy(() -> new ModelClient(PROPS, low, low).json("gpt-6.1-terra", "s", "u", String.class, 8000, "low")).isInstanceOf(OutputLimitException.class);
        assertThat(low.efforts).containsExactly("low");
        assertThat(ModelClient.lowerEffort("high")).isEqualTo("medium");
        assertThat(ModelClient.lowerEffort(null)).isNull();
    }
}
