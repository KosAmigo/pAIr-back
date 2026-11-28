package com.pair.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서버 추론 감독 설정 (application.yml 의 pair.vision). 기본은 전부 꺼짐이라 기존 PC 분석 동작이 그대로다.
 * enabled(PAIR_SERVER_VISION_ENABLED) 와 회차별 플래그(exam_sessions.server_vision) 가 둘 다 켜져야 그 회차가 서버 추론을 쓴다.
 * 추론 서버 키·이벤트 서명 비밀은 환경 변수로만 받고 로그에 남기지 않는다.
 *
 * @param inferenceUrl 추론 서버 주소 (비우면 가짜 구현만 있다)
 * @param inferenceKey 백엔드 → 추론 서버 인증 키 (Bearer)
 * @param eventHmacSecret 추론 서버 → 백엔드 이벤트 콜백 서명 비밀, 키 번호 k1 (비어 있으면 k1 은 없는 키로 본다)
 * @param eventHmacSecretNext 같은 콜백의 두 번째 비밀, 키 번호 k2 (무중단 교체용)
 * @param readyzIntervalMs 추론 서버 상태 확인 주기
 * @param failureThreshold 연속 실패가 이 횟수에 이르면 감독 중단
 * @param noStateMs 프레임은 오는데 추론 서버의 생존 신호(state)가 이만큼 없으면 그 응시자는 감독 중단
 * @param streamRetentionDays 상시 저장분 보관일 (시험 종료 뒤, 연장 없음)
 * @param clipRetentionDays 이벤트 구간·대표 사진 보관일 (시험 종료 뒤)
 * @param extensionDays 이벤트 구간 보관 연장 1회당 일수
 * @param maxExtensions 연장 최대 횟수
 * @param extendWindowDays 삭제 예정 며칠 전부터 연장할 수 있는지
 * @param phoneViewable 휴대폰 사진을 감독관이 사후에 볼 수 있는지 (사용자 확인 대기 중인 가정이라 설정으로 막을 수 있게 한다)
 * @param rateFps 클라이언트에 알려 주는 전송 간격 (장/초)
 * @param webcamMaxEdge 웹캠 사진 긴 변(px)
 * @param webcamQuality 웹캠 JPEG 화질 상한
 * @param phoneMaxEdge 휴대폰 사진 긴 변(px)
 */
@ConfigurationProperties(prefix = "pair.vision")
public record VisionProperties(
        boolean enabled,
        String inferenceUrl,
        String inferenceKey,
        String eventHmacSecret,
        String eventHmacSecretNext,
        long readyzIntervalMs,
        int failureThreshold,
        long noStateMs,
        int streamRetentionDays,
        int clipRetentionDays,
        int extensionDays,
        int maxExtensions,
        int extendWindowDays,
        boolean phoneViewable,
        double rateFps,
        int webcamMaxEdge,
        double webcamQuality,
        int phoneMaxEdge) {

    public VisionProperties {
        if (inferenceUrl == null) inferenceUrl = "";
        if (inferenceKey == null) inferenceKey = "";
        if (eventHmacSecret == null) eventHmacSecret = "";
        if (eventHmacSecretNext == null) eventHmacSecretNext = "";
        if (readyzIntervalMs <= 0) readyzIntervalMs = 5_000;
        if (failureThreshold <= 0) failureThreshold = 3;
        if (noStateMs <= 0) noStateMs = 10_000;
        if (streamRetentionDays <= 0) streamRetentionDays = 3;
        if (clipRetentionDays <= 0) clipRetentionDays = 3;
        if (extensionDays <= 0) extensionDays = 3;
        if (maxExtensions <= 0) maxExtensions = 2;
        if (extendWindowDays <= 0) extendWindowDays = 1;
        if (rateFps <= 0) rateFps = 1;
        if (webcamMaxEdge <= 0) webcamMaxEdge = 400;
        if (webcamQuality <= 0) webcamQuality = 0.55;
        if (phoneMaxEdge <= 0) phoneMaxEdge = 512;
    }

    public static VisionProperties defaults() {
        return new VisionProperties(false, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, 0, 0);
    }
}
