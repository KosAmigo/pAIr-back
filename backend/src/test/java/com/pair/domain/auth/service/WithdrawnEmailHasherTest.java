package com.pair.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class WithdrawnEmailHasherTest {
    @Test
    void requiresAValidKeyAndProducesDeterministicKeyedHashes() {
        assertThatThrownBy(() -> new WithdrawnEmailHasher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WithdrawnEmailHasher("not base64")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WithdrawnEmailHasher(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);

        byte[] firstKey = new byte[32];
        byte[] secondKey = new byte[32];
        secondKey[0] = 1;
        WithdrawnEmailHasher first = new WithdrawnEmailHasher(Base64.getEncoder().encodeToString(firstKey));
        WithdrawnEmailHasher second = new WithdrawnEmailHasher(Base64.getEncoder().encodeToString(secondKey));
        String hash = first.hash("user@example.com");
        assertThat(hash).matches("withdrawn:[0-9a-f]{64}").isEqualTo(first.hash("user@example.com"));
        assertThat(hash).isNotEqualTo(first.hash("other@example.com")).isNotEqualTo(second.hash("user@example.com"));
    }
}
