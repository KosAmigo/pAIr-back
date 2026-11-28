package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.exam.MutableClock;
import com.pair.global.config.VisionProperties;

class VisionSignatureServiceTest {

    static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    final MutableClock clock = new MutableClock(NOW);
    final VisionProperties props = new VisionProperties(true, "", "", "secret-one", "secret-two", 0, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, 0, 0);
    final VisionSignatureService service = new VisionSignatureService(props, clock);
    final byte[] body = "{\"clientId\":\"v1\"}".getBytes(StandardCharsets.UTF_8);
    final String ts = Long.toString(NOW.getEpochSecond());

    @Test
    @DisplayName("올바른 서명은 통과하고, 두 번째 비밀(k2)도 동시에 유효하다")
    void valid() {
        assertThat(service.verify("k1", ts, VisionSignatureService.sign("secret-one", ts, body), body)).isEqualTo(VisionSignatureService.Result.OK);
        assertThat(service.verify("k2", ts, VisionSignatureService.sign("secret-two", ts, body), body)).isEqualTo(VisionSignatureService.Result.OK);
    }

    @Test
    @DisplayName("본문이 바뀌거나 비밀이 다르면 BAD_SIGNATURE")
    void tampered() {
        String sig = VisionSignatureService.sign("secret-one", ts, body);
        assertThat(service.verify("k1", ts, sig, "{}".getBytes(StandardCharsets.UTF_8))).isEqualTo(VisionSignatureService.Result.BAD_SIGNATURE);
        assertThat(service.verify("k2", ts, sig, body)).isEqualTo(VisionSignatureService.Result.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("헤더가 없으면 MISSING, 모르는 키 번호·빈 비밀은 UNKNOWN_KEY_ID")
    void missingAndUnknown() {
        assertThat(service.verify(null, ts, "x", body)).isEqualTo(VisionSignatureService.Result.MISSING);
        assertThat(service.verify("k9", ts, "x", body)).isEqualTo(VisionSignatureService.Result.UNKNOWN_KEY_ID);
        var noSecret = new VisionSignatureService(VisionProperties.defaults(), clock);
        assertThat(noSecret.verify("k1", ts, "x", body)).isEqualTo(VisionSignatureService.Result.UNKNOWN_KEY_ID);
    }

    @Test
    @DisplayName("타임스탬프가 서버 시각 ±60초를 벗어나면 STALE_TIMESTAMP")
    void stale() {
        String old = Long.toString(NOW.getEpochSecond() - 61);
        assertThat(service.verify("k1", old, VisionSignatureService.sign("secret-one", old, body), body)).isEqualTo(VisionSignatureService.Result.STALE_TIMESTAMP);
        String edge = Long.toString(NOW.getEpochSecond() - 60);
        assertThat(service.verify("k1", edge, VisionSignatureService.sign("secret-one", edge, body), body)).isEqualTo(VisionSignatureService.Result.OK);
    }
}
