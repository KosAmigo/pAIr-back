package com.pair.domain.attempt.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.pair.domain.attempt.exception.TooManyRunsException;
import com.pair.global.config.PairProperties;

/** 빌드·실행 횟수 제한: 키마다 1분에 정해진 횟수까지, 1분이 지나면 다시 누를 수 있다 */
class RunRateLimitServiceTest {

    /** 테스트가 시각을 직접 움직인다 */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private RunRateLimitService service(int perMinute) {
        var run = new PairProperties.Run(60, null, null, 0, 0, null, null, perMinute);
        return new RunRateLimitService(new PairProperties(null, null, null, null, run, null, null), clock);
    }

    @Test
    void 허용한_횟수까지는_통과하고_넘으면_429용_예외() {
        var limiter = service(3);
        for (int i = 0; i < 3; i++) limiter.check("user:1");
        assertThatThrownBy(() -> limiter.check("user:1")).isInstanceOf(TooManyRunsException.class)
                .hasMessageContaining("1분에 3번").hasMessageContaining("초 뒤");
    }

    @Test
    void 넘은_요청은_세지_않아서_1분이_지나면_바로_다시_된다() {
        var limiter = service(2);
        limiter.check("a");
        clock.advance(Duration.ofSeconds(10));
        limiter.check("a");
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> limiter.check("a")).isInstanceOf(TooManyRunsException.class);
        // 첫 요청이 1분을 채우는 시점(50초 더)에 한 자리가 난다. 거절한 5번은 세지 않았으므로 나머지는 한 번만 허용된다
        clock.advance(Duration.ofSeconds(50));
        assertThatCode(() -> limiter.check("a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("a")).isInstanceOf(TooManyRunsException.class);
    }

    @Test
    void 키마다_따로_센다() {
        var limiter = service(1);
        limiter.check("user:1");
        assertThatCode(() -> limiter.check("user:2")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("attempt:7")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("user:1")).isInstanceOf(TooManyRunsException.class);
    }

    @Test
    void 기다려야_하는_시간을_알려_준다() {
        var limiter = service(1);
        limiter.check("a");
        clock.advance(Duration.ofSeconds(45));
        // 첫 요청 후 45초가 지났으니 15초 남았다(올림해서 16초)
        assertThatThrownBy(() -> limiter.check("a")).hasMessageContaining("16초 뒤");
    }

    @Test
    void 설정을_안_하면_1분에_10번이다() {
        var limiter = service(0);
        for (int i = 0; i < 10; i++) limiter.check("a");
        assertThatThrownBy(() -> limiter.check("a")).isInstanceOf(TooManyRunsException.class);
    }

    @Test
    void 키가_많이_쌓여도_1분이_지난_키는_쓸어_낸다() {
        var limiter = service(5);
        for (int i = 0; i < 1000; i++) limiter.check("k" + i);
        clock.advance(Duration.ofMinutes(2));
        // 1000개째 키에서 쓸어 내므로 이후에도 새 키가 정상 동작한다
        assertThatCode(() -> limiter.check("new")).doesNotThrowAnyException();
    }
}
