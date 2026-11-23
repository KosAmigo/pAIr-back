package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

/** 감독 사진 보관 현황. expiresAt 이 삭제 예정 시각, canExtend 는 지금 연장 버튼을 눌러도 되는지 */
public record RetentionResponse(Instant expiresAt, int extensionsUsed, int maxExtensions, int extensionDays, boolean canExtend,
        List<Entry> history) {
    public record Entry(int seq, Long extendedBy, Instant extendedAt, int days, Instant expiresBefore, Instant expiresAfter) {}
}
