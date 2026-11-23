package com.pair.exam.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrameRateLimiterTest {

    @Test
    @DisplayName("1초에 허용 수까지만 통과시키고 넘는 프레임은 거절한다. 다음 1초가 되면 다시 받는다")
    void limitsPerSecond() {
        var limiter = new FrameRateLimiter(3, 10);
        assertThat(limiter.allow(1_000)).isTrue();
        assertThat(limiter.allow(1_100)).isTrue();
        assertThat(limiter.allow(1_200)).isTrue();
        assertThat(limiter.allow(1_300)).isFalse();
        assertThat(limiter.allow(2_000)).isTrue();
    }

    @Test
    @DisplayName("한 창에서 초과가 한도를 넘으면 남용으로 본다")
    void abuseWhenTooManyExcess() {
        var limiter = new FrameRateLimiter(1, 3);
        limiter.allow(0);
        for (int i = 0; i < 3; i++) limiter.allow(10 + i);
        assertThat(limiter.abusive()).isFalse();
        limiter.allow(20);
        assertThat(limiter.abusive()).isTrue();
    }
}
