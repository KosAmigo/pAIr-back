package com.pair.taskgen;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 과제 파일(PDF) 저장소 경계. SnapshotStorage 와 같은 방식이다.
 * 개발 중에는 {@link LocalTaskFileStorage}(pilot-data 폴더), 운영에서는 {@link S3TaskFileStorage}.
 * 설정 pair.taskgen.file-storage = local(기본) | s3. S3 접속 값은 pair.exam.s3.* 를 그대로 쓴다.
 * key 는 서버가 만든 값만 쓴다: "task-files/{과제 번호}/{문서 순번}.pdf".
 */
public interface TaskFileStorage {

    Pattern KEY = Pattern.compile("task-files/\\d{1,19}/\\d{1,4}\\.pdf");

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    /** 없는 키는 조용히 넘어간다 (두 번 지워도 안전) */
    void delete(String key);

    static String keyOf(long taskId, int index) {
        return "task-files/" + taskId + "/" + index + ".pdf";
    }

    static void requireValid(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("잘못된 파일 키");
    }
}
