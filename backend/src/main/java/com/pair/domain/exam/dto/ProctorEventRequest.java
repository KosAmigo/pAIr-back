package com.pair.domain.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import com.pair.domain.exam.entity.ProctorEvent;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ProctorEventRequest(
        @NotNull ProctorEvent.Type type,
        @NotNull ProctorEvent.Source source,
        @NotNull Instant startedAt,
        @Min(0) @Max(3_600_000) int durationMs,
        @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
        JsonNode detail,
        /** data:image/jpeg;base64,... 또는 base64 본문. 카메라 이벤트만 */
        String snapshot,
        /** 브라우저가 이벤트마다 만든 고유값(UUID). 재전송해도 이벤트가 두 건이 되지 않게 한다. 없어도 받는다 */
        @Pattern(regexp = "[A-Za-z0-9-]{8,36}") String clientId) {

    /** clientId 없이 만드는 편의 생성자 (옛 클라이언트·테스트) */
    public ProctorEventRequest(ProctorEvent.Type type, ProctorEvent.Source source, Instant startedAt, int durationMs,
            BigDecimal confidence, JsonNode detail, String snapshot) {
        this(type, source, startedAt, durationMs, confidence, detail, snapshot, null);
    }
}
