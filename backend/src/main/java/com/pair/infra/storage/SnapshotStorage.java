package com.pair.infra.storage;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 회차 파일 저장소 경계: 감독 사진과 대상자 CSV 원본.
 * 운영에서는 S3 ({@link S3SnapshotStorage}), 개발 중에는 {@link LocalSnapshotStorage} 가 pilot-data 폴더에 둔다.
 * key 는 서버가 만든 값만 쓴다: "exam-snapshots/{회차}/{이벤트}.jpg", "participant-csv/{회차}/{업로드 시각 ms}.csv".
 * (팀원 1 의 공통 파일 저장소가 생기면 그쪽으로 옮긴다)
 */
public interface SnapshotStorage {

    /** 서버가 만든 키 모양. 경로 조작을 막는 데도 쓴다 */
    Pattern KEY = Pattern.compile("exam-clips/\\d{1,19}/\\d{1,19}/\\d{1,19}\\.jpg|exam-snapshots/\\d{1,19}/\\d{1,19}\\.jpg|env-check/\\d{1,19}/\\d{1,19}\\.jpg|exam-stream/\\d{1,19}/\\d{1,19}/(?:webcam|phone)/\\d{1,19}\\.pack|participant-csv/\\d{1,19}/\\d{1,19}\\.csv");

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    /** 없는 키는 조용히 넘어간다 (두 번 지워도 안전) */
    void delete(String key);

    static String keyOf(long sessionId, long eventId) {
        return "exam-snapshots/" + sessionId + "/" + eventId + ".jpg";
    }

    /** 이벤트 증거 구간 사진 1장 (이벤트 앞뒤) */
    static String clipKeyOf(long sessionId, long eventId, long frameId) {
        return "exam-clips/" + sessionId + "/" + eventId + "/" + frameId + ".jpg";
    }

    /** 환경 점검 사진(귀 왼쪽·오른쪽, 손목) */
    static String envCheckKeyOf(long sessionId, long photoId) {
        return "env-check/" + sessionId + "/" + photoId + ".jpg";
    }

    /** 사진 상시 저장 묶음 (채널 webcam|phone, 10초 정렬 구간 시작 ms) */
    static String streamKeyOf(long sessionId, long participantId, String channel, long startMs) {
        return "exam-stream/" + sessionId + "/" + participantId + "/" + channel + "/" + startMs + ".pack";
    }

    static String csvKeyOf(long sessionId, long uploadedAtMillis) {
        return "participant-csv/" + sessionId + "/" + uploadedAtMillis + ".csv";
    }

    static String contentType(String key) {
        return key.endsWith(".csv") ? "text/csv" : key.endsWith(".pack") ? "application/octet-stream" : "image/jpeg";
    }

    static void requireValid(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("잘못된 파일 키");
    }
}
