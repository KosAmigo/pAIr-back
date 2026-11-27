package com.pair.domain.attempt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.pair.domain.attempt.exception.TooManyChatsException;

/** AI 채팅 분당 제한: 1인 한도와 서비스 전체 한도를 함께 보고, 1분이 지나면 다시 보낼 수 있다 */
class ChatRateLimitServiceTest {

    private final RunRateLimitServiceTest.MutableClock clock = new RunRateLimitServiceTest.MutableClock();

    @Test
    void 한_사람은_1분에_정한_횟수까지_넘으면_기다릴_시간과_함께_429() {
        var limiter = new ChatRateLimitService(clock, 10, 300);
        for (int i = 0; i < 10; i++) limiter.check("user:1");
        clock.advance(Duration.ofSeconds(20));
        assertThatThrownBy(() -> limiter.check("user:1")).isInstanceOf(TooManyChatsException.class)
                .hasMessageContaining("1분에 10번").hasMessageContaining("41초 뒤")
                .satisfies(e -> assertThat(((TooManyChatsException) e).getRetryAfterSeconds()).isEqualTo(41));
        // 다른 사람은 영향을 받지 않는다
        assertThatCode(() -> limiter.check("user:2")).doesNotThrowAnyException();
    }

    @Test
    void 서비스_전체_한도를_넘으면_처음_보내는_사람도_429() {
        var limiter = new ChatRateLimitService(clock, 10, 300);
        for (int user = 0; user < 30; user++) {
            for (int i = 0; i < 10; i++) limiter.check("user:" + user);
        }
        assertThatThrownBy(() -> limiter.check("user:new")).isInstanceOf(TooManyChatsException.class)
                .hasMessageContaining("몰려");
    }

    @Test
    void 막힌_요청은_세지_않는다() {
        var limiter = new ChatRateLimitService(clock, 2, 3);
        limiter.check("user:1");
        limiter.check("user:2");
        limiter.check("user:2");
        // 전체 한도(3)에 막힌 요청은 user:1 의 횟수에도 들어가지 않는다
        assertThatThrownBy(() -> limiter.check("user:1")).hasMessageContaining("몰려");
        assertThatThrownBy(() -> limiter.check("user:1")).hasMessageContaining("몰려");
        clock.advance(Duration.ofSeconds(61));
        limiter.check("user:1");
        assertThatCode(() -> limiter.check("user:1")).doesNotThrowAnyException();
    }

    @Test
    void 일분이_지나면_다시_보낼_수_있다() {
        var limiter = new ChatRateLimitService(clock, 3, 300);
        for (int i = 0; i < 3; i++) limiter.check("attempt:7");
        assertThatThrownBy(() -> limiter.check("attempt:7")).isInstanceOf(TooManyChatsException.class);
        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.check("attempt:7")).doesNotThrowAnyException();
    }

    @Test
    void 한도는_양수여야_한다() {
        assertThatThrownBy(() -> new ChatRateLimitService(clock, 0, 300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRateLimitService(clock, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
