package com.pair.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import com.pair.global.config.IpLimitProperties;
import com.pair.global.config.LoginLimitProperties;
import com.pair.global.error.TooManyRequestsException;

/** IP별 요청 제한: 1시간에 메일 3번, 가입 4번, 문의 2번 */
class IpRateLimitInterceptorTest {

    private static final String MAIL = "/api/auth/email-verifications";
    private static final String INVITE_MAIL = "/api/company-invites/{token}/email-verifications";
    private static final String SIGNUP = "/api/auth/signup";
    private static final String INQUIRY = "/api/company-inquiries";

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final IpRateLimitInterceptor interceptor = new IpRateLimitInterceptor(
            new IpLimitProperties(Duration.ofHours(1), 3, 4, 2), new ClientIpResolver(new LoginLimitProperties(null, 0, 0, 0, 1)), clock);

    private boolean call(String method, String pattern, String ip) {
        var request = new MockHttpServletRequest(method, "/x");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.addHeader("X-Forwarded-For", ip);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private void blocked(String pattern, String ip) {
        assertThatThrownBy(() -> call("POST", pattern, ip)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void 한도를_넘으면_막고_기간이_지나면_풀린다() {
        for (int i = 0; i < 2; i++) assertThat(call("POST", INQUIRY, "1.1.1.1")).isTrue();
        assertThatThrownBy(() -> call("POST", INQUIRY, "1.1.1.1")).isInstanceOfSatisfying(TooManyRequestsException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(3601));
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(call("POST", INQUIRY, "1.1.1.1")).isTrue();
    }

    @Test
    void 막힌_요청은_대기_시간을_늘리지_않는다() {
        for (int i = 0; i < 2; i++) call("POST", INQUIRY, "1.1.1.1");
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        for (int i = 0; i < 10; i++) blocked(INQUIRY, "1.1.1.1");
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(call("POST", INQUIRY, "1.1.1.1")).isTrue();
    }

    @Test
    void 같은_종류의_API_는_횟수를_합친다() {
        call("POST", MAIL, "1.1.1.1");
        call("POST", INVITE_MAIL, "1.1.1.1");
        call("POST", MAIL, "1.1.1.1");
        blocked(INVITE_MAIL, "1.1.1.1");
    }

    @Test
    void 다른_IP_와_다른_종류에는_영향이_없다() {
        for (int i = 0; i < 2; i++) call("POST", INQUIRY, "1.1.1.1");
        blocked(INQUIRY, "1.1.1.1");
        assertThat(call("POST", INQUIRY, "2.2.2.2")).isTrue();
        assertThat(call("POST", SIGNUP, "1.1.1.1")).isTrue();
    }

    @Test
    void 꾸민_IP_를_앞에_붙여도_같은_사람으로_센다() {
        call("POST", INQUIRY, "9.9.9.1, 1.1.1.1");
        call("POST", INQUIRY, "9.9.9.2, 1.1.1.1");
        blocked(INQUIRY, "9.9.9.3, 1.1.1.1");
    }

    @Test
    void 조회와_대상이_아닌_경로는_세지_않는다() {
        for (int i = 0; i < 20; i++) {
            assertThat(call("GET", INQUIRY, "1.1.1.1")).isTrue();
            assertThat(call("POST", "/api/auth/login", "1.1.1.1")).isTrue();
        }
        assertThat(call("POST", INQUIRY, "1.1.1.1")).isTrue();
    }
}
