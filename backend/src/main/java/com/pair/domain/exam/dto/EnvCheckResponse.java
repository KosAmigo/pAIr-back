package com.pair.domain.exam.dto;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;

/** 콘솔이 보는 환경 점검 결과. result 는 응시자 브라우저가 올린 점검 항목 JSON 그대로 */
public record EnvCheckResponse(Source source, Instant updatedAt, JsonNode result) {

    /** 점검 결과의 출처: LIVE 입장 대기 중 진행 상태, SAVED 동의와 함께 저장된 결과 */
    public enum Source { LIVE, SAVED }
}
