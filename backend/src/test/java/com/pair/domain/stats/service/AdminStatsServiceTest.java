package com.pair.domain.stats.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 점수 구간 경계와 평균(소수 첫째 자리) */
class AdminStatsServiceTest {

    @Test
    void scoresFallIntoBandsByLowerBound() {
        AdminStatsService.Scores s = AdminStatsService.summarize(List.of(0.0, 39.9, 40.0, 59.5, 60.0, 79.9, 80.0, 100.0));

        assertThat(s.scored()).isEqualTo(8);
        assertThat(s.bands()).extracting(b -> b.range()).containsExactly("0-39", "40-59", "60-79", "80-100");
        assertThat(s.bands()).extracting(b -> b.count()).containsExactly(2L, 2L, 2L, 2L);
        assertThat(s.average()).isEqualTo(57.4);
    }

    @Test
    void noScoresMeansNoAverage() {
        AdminStatsService.Scores s = AdminStatsService.summarize(List.of());

        assertThat(s.scored()).isZero();
        assertThat(s.average()).isNull();
        assertThat(s.bands()).allMatch(b -> b.count() == 0);
    }
}
