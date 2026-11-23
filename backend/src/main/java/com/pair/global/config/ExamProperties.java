package com.pair.global.config;

import com.pair.domain.exam.service.LanAddressService;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 실전 시험 회차 설정 (application.yml 의 pair.exam). */
@ConfigurationProperties(prefix = "pair.exam")
public record ExamProperties(
        String entryBaseUrl,
        String mailFrom,
        long autoSubmitIntervalMs,
        long snapshotRetentionDays,
        int snapshotMaxBytes,
        int maxParticipantsPerUpload) {

    public ExamProperties {
        if (entryBaseUrl == null || entryBaseUrl.isBlank()) entryBaseUrl = "https://" + LanAddressService.find().orElse("localhost") + ":5173"; // 개발 서버(npm run dev)는 HTTPS, 같은 네트워크 팀원도 열 수 있게 사내 IP
        if (mailFrom == null || mailFrom.isBlank()) mailFrom = "no-reply@pair.test";
        if (autoSubmitIntervalMs <= 0) autoSubmitIntervalMs = 2000;
        if (snapshotRetentionDays <= 0) snapshotRetentionDays = 7;
        if (snapshotMaxBytes <= 0) snapshotMaxBytes = 200_000;
        if (maxParticipantsPerUpload <= 0) maxParticipantsPerUpload = 1000;
    }

    public static ExamProperties defaults() {
        return new ExamProperties(null, null, 0, 0, 0, 0);
    }
}
