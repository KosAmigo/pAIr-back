package com.pair.domain.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.scoring.repository.ScoringResultRepository;

class ScoringRecoveryServiceTest {

    static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");

    AttemptRepository attempts = mock(AttemptRepository.class);
    ScoringRepository scorings = mock(ScoringRepository.class);
    ScoringResultRepository records = mock(ScoringResultRepository.class);
    ScoringService scoring = mock(ScoringService.class);

    ScoringRecoveryService service(boolean enabled) {
        return new ScoringRecoveryService(attempts, scorings, records, scoring, Clock.fixed(NOW, ZoneOffset.UTC), 10, enabled);
    }

    @Test
    @DisplayName("SCORING 에 남은 응시를 다시 채점에 맡기고, 끊긴 채점 기록은 ERROR 로 닫는다")
    void resumesStuckAttempts() {
        when(attempts.findIdsByStatus(AttemptStateDto.Status.SCORING)).thenReturn(List.of(52L, 53L));
        Scoring running = new Scoring(52L, 1L, NOW.minusSeconds(600));
        ReflectionTestUtils.setField(running, "id", 7L);
        when(scorings.findByAttemptIdInAndStatus(List.of(52L, 53L), "RUNNING")).thenReturn(List.of(running));

        assertThat(service(true).recover()).isEqualTo(2);

        verify(records).fail(7L, ScoringRecoveryService.INTERRUPTED);
        verify(scoring).scoreAsync("52");
        verify(scoring).scoreAsync("53");
    }

    @Test
    @DisplayName("24시간 채점 한도를 넘은 응시는 다시 맡기지 않고 ERROR 로 둔다")
    void marksFailedWhenOverLimit() {
        when(attempts.findIdsByStatus(AttemptStateDto.Status.SCORING)).thenReturn(List.of(52L));
        when(scorings.countByAttemptIdAndStartedAtAfter(eq(52L), any())).thenReturn(10L);

        assertThat(service(true).recover()).isZero();

        verify(scoring, never()).scoreAsync(anyString());
        verify(scoring).markFailed(eq("52"), any());
    }

    @Test
    @DisplayName("SCORING 응시가 없으면 아무것도 하지 않는다")
    void noopWhenNothingStuck() {
        when(attempts.findIdsByStatus(AttemptStateDto.Status.SCORING)).thenReturn(List.of());

        assertThat(service(true).recover()).isZero();

        verifyNoInteractions(scorings, records, scoring);
    }

    @Test
    @DisplayName("꺼져 있으면 서버가 떠도 복구하지 않는다")
    void disabled() {
        service(false).onReady();
        verifyNoInteractions(attempts, scoring);
    }

    @Test
    @DisplayName("복구 중 오류가 나도 서버 시작을 막지 않는다")
    void swallowsErrorsOnStartup() {
        when(attempts.findIdsByStatus(any())).thenThrow(new IllegalStateException("db down"));
        service(true).onReady(); // 예외가 밖으로 나오지 않아야 한다
    }
}
