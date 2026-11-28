package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.exam.MutableClock;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.repository.SnapshotRetentionExtensionRepository;
import com.pair.global.config.ExamProperties;
import com.pair.global.config.VisionProperties;

class SnapshotRetentionVisionTest {

    static final Instant ENDS = Instant.parse("2026-11-25T12:00:00Z");
    final SnapshotRetentionService retention = new SnapshotRetentionService(mock(ExamSessionRepository.class),
            mock(SnapshotRetentionExtensionRepository.class), ExamProperties.defaults(), VisionProperties.defaults(), new MutableClock(ENDS));

    ExamSession session(boolean serverVision) {
        ExamSession s = new ExamSession(1L, "t", ENDS.minusSeconds(3600), ENDS, true, List.of(1L), 1L, ENDS.minusSeconds(7200));
        s.changeServerVision(serverVision);
        return s;
    }

    @Test
    @DisplayName("서버 추론이 꺼진 회차는 기존 규칙 그대로: 7일, 연장 1번에 7일")
    void legacyRules() {
        ExamSession s = session(false);
        assertThat(retention.expiryOf(s, 0)).isEqualTo(ENDS.plus(Duration.ofDays(7)));
        assertThat(retention.expiryOf(s, 2)).isEqualTo(ENDS.plus(Duration.ofDays(21)));
    }

    @Test
    @DisplayName("서버 추론 회차는 3일, 연장 1번에 3일(최대 2번이면 9일)")
    void visionRules() {
        ExamSession s = session(true);
        assertThat(retention.expiryOf(s, 0)).isEqualTo(ENDS.plus(Duration.ofDays(3)));
        assertThat(retention.expiryOf(s, 2)).isEqualTo(ENDS.plus(Duration.ofDays(9)));
    }

    @Test
    @DisplayName("삭제 배치 후보를 거를 가장 짧은 보관일은 3일")
    void shortest() {
        assertThat(retention.shortestBaseDays()).isEqualTo(3);
    }
}
