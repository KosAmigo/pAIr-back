package com.pair.domain.exam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.domain.exam.service.VisionSignatureService;
import com.pair.global.config.ExamIntegrationTest;

/** 추론 서버 이벤트 콜백(/internal/vision/events)의 서명·형식 거절 경로. 스위치는 테스트 프로필에서만 켜져 있다 */
class VisionInternalApiIntegrationTest extends ExamIntegrationTest {

    static final String SECRET = "test-callback-secret";
    @Autowired MockMvc mvc;

    private MockHttpServletRequestBuilder signed(String keyId, String secret, long tsOffsetSec, String body) {
        String ts = Long.toString(Instant.now().getEpochSecond() + tsOffsetSec);
        return post("/internal/vision/events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Vision-Key-Id", keyId).header("X-Vision-Timestamp", ts)
                .header("X-Vision-Signature", VisionSignatureService.sign(secret, ts, body.getBytes(StandardCharsets.UTF_8)))
                .content(body);
    }

    @Test
    @DisplayName("서명 헤더가 없으면 401 MISSING_SIGNATURE")
    void missing() throws Exception {
        mvc.perform(post("/internal/vision/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MISSING_SIGNATURE"));
    }

    @Test
    @DisplayName("잘못된 서명·오래된 타임스탬프·모르는 키 번호는 403")
    void forbidden() throws Exception {
        mvc.perform(signed("k1", "wrong-secret", 0, "{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BAD_SIGNATURE"));
        mvc.perform(signed("k1", SECRET, -120, "{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STALE_TIMESTAMP"));
        mvc.perform(signed("k7", SECRET, 0, "{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNKNOWN_KEY_ID"));
    }

    @Test
    @DisplayName("서명이 맞아도 필드가 틀리면 400 INVALID_EVENT, 모르는 유형·SUPERVISION_PAUSED 도 거절")
    void invalidEvent() throws Exception {
        mvc.perform(signed("k1", SECRET, 0, "{\"clientId\":\"short\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
        String base = "{\"clientId\":\"v3b7c1f0a9d2e4458a1c0f6e7d2b9a41c\",\"sessionKey\":\"Zk3xQmV0aGlzSXNBS2V5MQ\",\"source\":\"PHONE_CAM\","
                + "\"startedAtMs\":1764000123456,\"durationMs\":7000,\"rulesVersion\":\"r1\",\"type\":\"";
        mvc.perform(signed("k1", SECRET, 0, base + "FUTURE_TYPE\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
        // TABLET·NOTE·SMARTWATCH 는 휴대폰(PHONE_CAM)에서만 만든다: PC_CAM 조합은 400
        mvc.perform(signed("k1", SECRET, 0, base.replace("PHONE_CAM", "PC_CAM") + "TABLET\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
        mvc.perform(signed("k1", SECRET, 0, base + "SUPERVISION_PAUSED\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
    }

    @Test
    @DisplayName("올바른 이벤트라도 모르는 sessionKey 면 404 UNKNOWN_SESSION_KEY")
    void unknownSessionKey() throws Exception {
        String body = "{\"clientId\":\"v3b7c1f0a9d2e4458a1c0f6e7d2b9a41c\",\"sessionKey\":\"Zk3xQmV0aGlzSXNBS2V5MQ\",\"type\":\"HANDS_OUT\",\"source\":\"PHONE_CAM\","
                + "\"startedAtMs\":1764000123456,\"durationMs\":7000,\"rulesVersion\":\"r1\"}";
        mvc.perform(signed("k1", SECRET, 0, body)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_SESSION_KEY"));
    }
}
