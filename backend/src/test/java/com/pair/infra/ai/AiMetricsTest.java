package com.pair.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
    }

    @Test
    void tokens_inAndOut_areCountedSeparately() {
        AiMetrics.tokens("OpenAI", "gpt-test", 120, 30);
        AiMetrics.tokens("OpenAI", "gpt-test", 80, 0);

        assertThat(registry.get("pair_ai_tokens_total").tag("direction", "in").counter().count()).isEqualTo(200);
        assertThat(registry.get("pair_ai_tokens_total").tag("direction", "out").counter().count()).isEqualTo(30);
    }

    @Test
    void call_recordsOutcomeAsTag() {
        AiMetrics.call("Claude 채점", "claude-test", true, 2_000_000_000L);
        AiMetrics.call("Claude 채점", "claude-test", false, 1_000_000_000L);

        assertThat(registry.get("pair_ai_call_seconds").tag("outcome", "ok").timer().count()).isEqualTo(1);
        assertThat(registry.get("pair_ai_call_seconds").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void springAiCall_whenCallFails_recordsErrorAndRethrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SpringAi.call("테스트 호출", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("pair_ai_call_seconds").tag("provider", "테스트 호출").tag("outcome", "error").timer().count()).isEqualTo(1);
    }
}
