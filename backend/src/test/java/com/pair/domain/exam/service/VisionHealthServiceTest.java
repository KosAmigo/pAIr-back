package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.VisionProperties;
import com.pair.infra.ai.InferenceClient;
import com.pair.infra.ai.StubInferenceClient;

class VisionHealthServiceTest {

    static class Switchable extends StubInferenceClient {
        final AtomicBoolean ready = new AtomicBoolean(true);
        Switchable() { super(new com.fasterxml.jackson.databind.ObjectMapper()); }
        @Override public boolean ready() { return ready.get(); }
    }

    final Switchable client = new Switchable();
    final VisionProperties props = new VisionProperties(true, "", "", "", "", 0, 3, 0, 0, 0, 0, 0, 0, true, 0, 0, 0, 0);
    final VisionHealthService health = new VisionHealthService(client, props);

    @Test
    @DisplayName("상태 확인이 연속 3번 실패해야 감독 중단으로 보고, 한 번 성공하면 복구된다")
    void downAfterConsecutiveFailures() {
        AtomicInteger changes = new AtomicInteger();
        health.onChange(changes::incrementAndGet);
        client.ready.set(false);

        health.probe();
        health.probe();
        assertThat(health.isUp()).isTrue(); // 2번째까지는 아직
        health.probe();
        assertThat(health.isUp()).isFalse();

        client.ready.set(true);
        health.probe();
        assertThat(health.isUp()).isTrue();
        assertThat(changes.get()).isEqualTo(2); // down, up 각 한 번
    }

    @Test
    @DisplayName("프레임 전달이 연속 실패해도 중단으로 보고, 성공하면 연속 횟수가 처음부터 다시 센다")
    void sendFailures() {
        health.recordSend(false);
        health.recordSend(false);
        health.recordSend(true);
        health.recordSend(false);
        health.recordSend(false);
        assertThat(health.isUp()).isTrue();
        health.recordSend(false);
        assertThat(health.isUp()).isFalse();
        assertThat(InferenceClient.Mode.values()).hasSize(2);
    }

    @Test
    @DisplayName("응시자 생존 신호 시각을 기억하고, 없으면 대체값을 쓴다")
    void lastState() {
        assertThat(health.lastState("k", 5L)).isEqualTo(5L);
        health.recordState("k", 99L);
        assertThat(health.lastState("k", 5L)).isEqualTo(99L);
    }
}
