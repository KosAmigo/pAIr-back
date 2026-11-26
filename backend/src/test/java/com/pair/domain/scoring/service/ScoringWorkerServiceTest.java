package com.pair.domain.scoring.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.scoring.dto.ScoringJobDto;
import com.pair.global.config.ScoringQueueProperties;

class ScoringWorkerServiceTest {

    ScoringQueueService queue = mock(ScoringQueueService.class);
    ScoringService scoring = mock(ScoringService.class);
    ScoringWorkerService worker;

    @BeforeEach
    void setUp() {
        ScoringQueueProperties props = new ScoringQueueProperties(true, 1, 3, Duration.ofSeconds(30), Duration.ofMinutes(15), null);
        worker = new ScoringWorkerService(queue, scoring, props, Duration.ofSeconds(5));
    }

    private static ScoringJobDto job(int attempt) {
        return new ScoringJobDto("1-0", "42", attempt);
    }

    @Test
    @DisplayName("Redis 에서 꺼내지 못하면 지표를 0 으로, 다시 되면 1 로 둔다")
    void queueUpFlagFollowsRedis() {
        when(queue.take(any())).thenThrow(new IllegalStateException("redis down"));
        org.assertj.core.api.Assertions.assertThat(worker.pollOnce()).isFalse();
        org.assertj.core.api.Assertions.assertThat(worker.queueUp()).isZero();

        org.mockito.Mockito.reset(queue);
        when(queue.take(any())).thenReturn(java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThat(worker.pollOnce()).isTrue();
        org.assertj.core.api.Assertions.assertThat(worker.queueUp()).isEqualTo(1);
    }

    @Test
    @DisplayName("채점에 성공하면 작업을 끝낸다")
    void completesOnSuccess() {
        worker.process(job(1));
        verify(scoring).score("42");
        verify(queue).complete(job(1));
    }

    @Test
    @DisplayName("이미 채점이 끝난 응시는 다시 채점하지 않고 작업만 끝낸다")
    void skipsAlreadyScoredAttempt() {
        when(scoring.isScored("42")).thenReturn(true);
        worker.process(job(2));
        verify(scoring, never()).score(anyString());
        verify(queue).complete(job(2));
    }

    @Test
    @DisplayName("실패하면 시도 횟수만큼 늘려 가며 다시 시도한다")
    void retriesWithGrowingDelay() {
        doThrow(new IllegalStateException("api down")).when(scoring).score("42");
        worker.process(job(1));
        worker.process(job(2));
        verify(queue).retryLater(job(1), Duration.ofSeconds(30));
        verify(queue).retryLater(job(2), Duration.ofSeconds(60));
        verify(queue, never()).fail(any(), anyString());
        verify(scoring, never()).markFailed(anyString(), any());
    }

    @Test
    @DisplayName("마지막 시도까지 실패하면 응시를 ERROR 로 두고 실패 보관함에 남긴다")
    void givesUpAfterMaxAttempts() {
        IllegalStateException error = new IllegalStateException("api down");
        doThrow(error).when(scoring).score("42");
        worker.process(job(3));
        verify(scoring).markFailed("42", error);
        verify(queue).fail(job(3), "api down");
        verify(queue, never()).retryLater(any(), any());
        verify(queue, never()).complete(any());
    }

    @Test
    @DisplayName("응시를 ERROR 로 표시하지 못해도 실패 보관은 한다")
    void keepsDeadLetterEvenIfMarkingFails() {
        IllegalStateException error = new IllegalStateException("api down");
        doThrow(error).when(scoring).score("42");
        doThrow(new IllegalStateException("no attempt")).when(scoring).markFailed(anyString(), any());
        worker.process(job(3));
        verify(queue).fail(job(3), "api down");
    }
}
