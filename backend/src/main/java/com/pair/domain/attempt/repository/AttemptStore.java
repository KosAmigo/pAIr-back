package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.attempt.service.ChatTurn;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 응시 기록(상태, 작업 파일, 대화, 작업 기록, 채점 리포트) 저장소.
 * 실제 응시는 DB(DbAttemptStore), 채점 툴 실험용 샘플 응시만 파일(FileAttemptStore, grader/samples)이다.
 */
public interface AttemptStore {

    /** 응시를 만들고 과제 원본 파일을 작업 파일로 넣는다 */
    void create(Attempt a, Map<String, String> files);

    void save(Attempt a);

    /** 없는 응시면 NoSuchElementException */
    Attempt load(String id);

    /** 채점 툴이 고를 수 있는 응시 ID 목록 */
    List<String> ids();

    /** 로그에 남길 응시 위치 (DB 행 / 파일 폴더) */
    String location(String id);

    // ── 작업 파일 ─────────────────────

    /** 경로 → 내용 (경로 순) */
    Map<String, String> readFiles(String id);

    /** 이미 있는 작업 파일만 고칠 수 있다 */
    void writeWorkspaceFile(String id, String path, String content);

    /** AI 수정 제안으로 빈 새 파일을 만든다. 이미 있으면 그대로 둔다 */
    void createWorkspaceFile(String id, String path);

    // ── 대화, 작업 기록 ─────────────────

    void appendTurn(String id, ChatTurn turn);

    List<ChatTurn> turns(String id);

    /** type 은 start, save, build, run, chat, chatEmpty, aiEdit, submit, followups, firstValid */
    void appendEvent(String id, String type, Map<String, Object> data);

    /** 이벤트마다 {type, at, ...data} 모양 (기록 순) */
    List<JsonNode> events(String id);

    // ── 리포트 ─────────────────────────

    void writeReport(String id, Object report);

    /** 없으면 null */
    JsonNode readReport(String id);

    /** 응시자가 고칠 수 없는 경로: 로그와 과제 자료. 화면(RealExam 의 readOnlyPath)과 같은 규칙이다 */
    static boolean readOnlyPath(String path) {
        return path != null && (path.startsWith("logs/") || path.startsWith("자료/"));
    }

    /** AI 수정 제안으로 새로 만들 수 있는 경로: .java, .md 만, logs/·자료/ 아래와 상위 경로(..)는 안 됨 */
    static boolean creatable(String path) {
        return path != null && (path.endsWith(".java") || path.endsWith(".md"))
                && !path.startsWith("/") && !path.contains("\\")
                && !path.startsWith("logs/") && !path.startsWith("자료/") && !path.contains("..");
    }
}
