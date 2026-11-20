package com.pair.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtTokensTest {
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final JwtTokens tokens = new JwtTokens(TEST_KEY);

    @Test
    void verifiesTokenTypeAndExpiry() {
        Instant now = Instant.now();
        String access = tokens.access(42, now);
        assertThat(tokens.verify(access, "access")).get().extracting(JwtTokens.VerifiedToken::userId).isEqualTo(42L);
        assertThat(tokens.verify(access, "refresh")).isEmpty();
        assertThat(tokens.verify(tokens.access(42, now.minus(JwtTokens.ACCESS_LIFETIME).minusSeconds(1)), "access"))
                .isEmpty();
        assertThat(tokens.verify(access + "x", "access")).isEmpty();
    }

    @Test
    void requiresAnAppropriatelySizedBase64Key() {
        assertThatThrownBy(() -> new JwtTokens("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokens("not base64")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokens(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);
    }
}
