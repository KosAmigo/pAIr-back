package com.pair.domain.exam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 추론 서버가 보내는 이벤트 후보 (내부 콜백 API, HMAC 서명 필요, 계약 §3-4). 사진은 붙이지 않는다.
 * 필드 검증은 서비스에서 한다(오류마다 code 를 돌려주려고). type·source 는 문자열로 받아 모르는 값도 요청 자체는 읽는다.
 */
public record VisionEventRequest(
        String clientId,
        String sessionKey,
        String type,
        String source,
        Long startedAtMs,
        Integer durationMs,
        Double confidence,
        JsonNode detail,
        @JsonProperty("final") Boolean finalState,
        String rulesVersion) {}
