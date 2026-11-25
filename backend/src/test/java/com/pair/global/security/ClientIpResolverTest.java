package com.pair.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.pair.global.config.LoginLimitProperties;

/** 보낸 사람이 꾸민 X-Forwarded-For 를 믿지 않는다 */
class ClientIpResolverTest {

    private static String resolve(int hops, String... forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        for (String value : forwarded) request.addHeader("X-Forwarded-For", value);
        return new ClientIpResolver(new LoginLimitProperties(null, 0, 0, 0, hops)).resolve(request);
    }

    @Test
    void 프록시가_붙인_오른쪽_값만_믿는다() {
        assertThat(resolve(1, "203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(resolve(1, "1.2.3.4, 203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(resolve(1, "1.2.3.4", "203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(resolve(2, "1.2.3.4, 203.0.113.5, 172.18.0.2")).isEqualTo("203.0.113.5");
    }

    @Test
    void 값이_없거나_모자라면_직접_연결된_주소를_쓴다() {
        assertThat(resolve(1)).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "")).isEqualTo("10.0.0.1");
        assertThat(resolve(2, "203.0.113.5")).isEqualTo("10.0.0.1");
        assertThat(resolve(1, "x".repeat(65))).isEqualTo("10.0.0.1");
        assertThat(resolve(0, "203.0.113.5")).isEqualTo("10.0.0.1");
    }
}
