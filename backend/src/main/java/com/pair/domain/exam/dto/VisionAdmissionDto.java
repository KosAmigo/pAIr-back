package com.pair.domain.exam.dto;

import java.time.Instant;

/**
 * 응시자 PC 가 /ws/vision 에 붙을 때의 입장 판정. result 가 OK 일 때만 나머지 값이 채워진다.
 * examStarted: 시험을 시작했으면 exam 모드, 아니면 check(입장 점검) 모드.
 */
public record VisionAdmissionDto(Result result, Long sessionId, Long participantId, String visionKey, boolean examStarted, Instant endsAt) {

    public enum Result { OK, UNAUTHORIZED, DISABLED, CONSENT_REQUIRED }

    public static VisionAdmissionDto denied(Result result) {
        return new VisionAdmissionDto(result, null, null, null, false, null);
    }
}
