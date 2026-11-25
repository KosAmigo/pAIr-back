package com.pair.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pair.domain.auth.exception.TooManyLoginAttemptsException;
import com.pair.global.config.LoginLimitProperties;
import com.pair.global.error.UnauthorizedException;

/** 로그인 실패 제한: 계정+IP 3번, 계정 5번, IP 6번, 10분 */
class LoginAttemptServiceTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service =
            new LoginAttemptService(new LoginLimitProperties(Duration.ofMinutes(10), 3, 5, 6, 1), clock);

    private void fail(String email, String ip) {
        assertThatThrownBy(() -> service.guard(email, ip, () -> { throw new UnauthorizedException("틀림"); }))
                .isInstanceOf(UnauthorizedException.class);
    }

    private String succeed(String email, String ip) {
        return service.guard(email, ip, () -> "ok");
    }

    private void blocked(String email, String ip) {
        assertThatThrownBy(() -> succeed(email, ip)).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void 한도를_넘으면_비밀번호가_맞아도_막고_기간이_지나면_풀린다() {
        for (int i = 0; i < 3; i++) fail("a@x.com", "1.1.1.1");
        assertThatThrownBy(() -> succeed("a@x.com", "1.1.1.1")).isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(601));
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(succeed("a@x.com", "1.1.1.1")).isEqualTo("ok");
    }

    @Test
    void 막힌_요청은_대기_시간을_늘리지_않는다() {
        for (int i = 0; i < 3; i++) fail("a@x.com", "1.1.1.1");
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        for (int i = 0; i < 10; i++) blocked("a@x.com", "1.1.1.1");
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(succeed("a@x.com", "1.1.1.1")).isEqualTo("ok");
    }

    @Test
    void 이메일_대소문자와_공백을_바꿔도_같은_계정으로_센다() {
        fail("a@x.com", "1.1.1.1");
        fail("A@X.COM", "1.1.1.1");
        fail(" a@x.com ", "1.1.1.1");
        blocked("a@x.com", "1.1.1.1");
    }

    @Test
    void 다른_곳의_실패로_정상_사용자가_바로_막히지_않는다() {
        for (int i = 0; i < 3; i++) fail("a@x.com", "1.1.1.1");
        assertThat(succeed("a@x.com", "2.2.2.2")).isEqualTo("ok");
        assertThat(succeed("b@x.com", "1.1.1.1")).isEqualTo("ok");
    }

    @Test
    void IP를_바꿔_가며_한_계정을_공격해도_막는다() {
        for (int i = 0; i < 5; i++) fail("a@x.com", "1.1.1." + i);
        blocked("a@x.com", "9.9.9.9");
    }

    @Test
    void 한_IP에서_여러_계정을_훑어도_막는다() {
        for (int i = 0; i < 6; i++) fail("u" + i + "@x.com", "1.1.1.1");
        blocked("new@x.com", "1.1.1.1");
    }

    @Test
    void 성공하면_그_사람의_실패_기록을_지우고_성공은_세지_않는다() {
        fail("a@x.com", "1.1.1.1");
        fail("a@x.com", "1.1.1.1");
        succeed("a@x.com", "1.1.1.1");
        fail("a@x.com", "1.1.1.1");
        fail("a@x.com", "1.1.1.1");
        for (int i = 0; i < 20; i++) succeed("a@x.com", "1.1.1.1");
    }

    @Test
    void 성공해도_IP_전체_실패_기록은_남는다() {
        for (int i = 0; i < 5; i++) fail("u" + i + "@x.com", "1.1.1.1");
        succeed("mine@x.com", "1.1.1.1");
        fail("u6@x.com", "1.1.1.1");
        blocked("u7@x.com", "1.1.1.1");
    }

    @Test
    void 서버_오류는_실패로_세지_않는다() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.guard("a@x.com", "1.1.1.1", () -> { throw new IllegalStateException("DB"); }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> succeed("a@x.com", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 동시에_보내도_한도만큼만_비밀번호를_확인한다() throws Exception {
        var checked = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                pool.submit(() -> {
                    start.await();
                    return service.guard("a@x.com", "1.1.1.1", () -> {
                        checked.incrementAndGet();
                        throw new UnauthorizedException("틀림");
                    });
                });
            }
            start.countDown();
        }
        assertThat(checked).hasValue(3);
    }
}
