package com.pair.domain.attempt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GradingQueueMetricsServiceTest {

    private final AttemptRepository attempts = mock(AttemptRepository.class);

    @Test
    void gauge_reportsScoringCount() {
        when(attempts.countByStatus(AttemptStateDto.Status.SCORING)).thenReturn(7L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GradingQueueMetricsService(attempts).bindTo(registry);

        assertThat(registry.get("pair_grading_pending").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void gauge_readsDbOnlyOncePerCacheWindow() {
        when(attempts.countByStatus(any())).thenReturn(3L);
        GradingQueueMetricsService service = new GradingQueueMetricsService(attempts);

        service.pending();
        service.pending();
        service.pending();

        verify(attempts, times(1)).countByStatus(AttemptStateDto.Status.SCORING);
    }

    @Test
    void gauge_whenDbFails_keepsPreviousValueInsteadOfThrowing() {
        when(attempts.countByStatus(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(new GradingQueueMetricsService(attempts).pending()).isEqualTo(0.0);
    }
}
