package com.pair.exam.service;

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
    Pattern KEY = Pattern.compile("exam-snapshots/\\d{1,19}/\\d{1,19}\\.jpg|env-scan/\\d{1,19}/\\d{1,19}\\.jpg|participant-csv/\\d{1,19}/\\d{1,19}\\.csv");

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    /** 없는 키는 조용히 넘어간다 (두 번 지워도 안전) */
    void delete(String key);

    static String keyOf(long sessionId, long eventId) {
        return "exam-snapshots/" + sessionId + "/" + eventId + ".jpg";
    }

    /** 환경 점검 사진(방 360도, 책상 위) */
    static String envScanKeyOf(long sessionId, long photoId) {
        return "env-scan/" + sessionId + "/" + photoId + ".jpg";
    }

    static String csvKeyOf(long sessionId, long uploadedAtMillis) {
        return "participant-csv/" + sessionId + "/" + uploadedAtMillis + ".csv";
    }

    static String contentType(String key) {
        return key.endsWith(".csv") ? "text/csv" : "image/jpeg";
    }

    static void requireValid(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("잘못된 파일 키");
    }
}
