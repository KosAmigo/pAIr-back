package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

/**
 * 감독 사진 보관 현황. expiresAt 이 삭제 예정 시각, canExtend 는 지금 연장 버튼을 눌러도 되는지.
 * extensionDays·maxExtensions 는 회차에 따라 다르다(서버 추론 회차 3일·2번, 아니면 7일·2번): 프론트는 상수 대신 이 값을 쓴다.
 */
public record RetentionResponse(Instant expiresAt, int extensionsUsed, int maxExtensions, int extensionDays, boolean canExtend,
        List<Entry> history,
        /** 회차 종료 뒤 기본 보관일 (서버 추론 회차 3, 아니면 7) */
        int baseDays,
        /** 삭제 예정 며칠 전부터 연장할 수 있는지 (서버 추론 회차 1, 아니면 2) */
        int extendableFromDaysBefore) {
    public record Entry(int seq, Long extendedBy, Instant extendedAt, int days, Instant expiresBefore, Instant expiresAfter) {}
}
