package com.pair.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 테스트 DB(H2)는 Flyway 를 돌리지 않으므로 V3(과제 행)·V6(과제 내용)과 같은 행을 넣는다.
 * 내용의 원본은 옛 classpath 폴더를 그대로 옮겨 둔 src/test/resources/task-fixtures/{code}/ 이다
 * (task.json, files/, hidden/). 이 폴더는 "DB 로 옮긴 결과가 옛 내용과 같은지" 비교하는 기준이기도 하다.
 * 테스트끼리 DB 를 같이 쓰므로 매번 공개(OPEN) 상태로 되돌리고 내용 행도 다시 넣는다.
 */
public final class TestTasks {

    public static final String SELF_DEV = "order-deadlock";
    public static final String SELF_BIZ = "pricing-plan";
    public static final String LIVE_DEV = "order-deadlock-live";
    public static final String LIVE_BIZ = "pricing-plan-live";

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestTasks() {}

    /** 과제 행만 (설명·settings 포함). 파일·후속 질문이 필요 없는 테스트용 */
    public static void seed(TaskRecordRepository repo) {
        Instant at = Instant.parse("2026-11-19T00:00:00Z");
        TaskRecord devSelf = new TaskRecord(1L, SELF_DEV, "주문 처리 장애 원인 분석", "DEV", 60, TaskRecord.Usage.SELF, TaskRecord.Status.OPEN, null, 1L, at);
        TaskRecord bizSelf = new TaskRecord(2L, SELF_BIZ, "신규 저가 요금제 출시 기획안", "BIZ", 60, TaskRecord.Usage.SELF, TaskRecord.Status.OPEN, null, 1L, at);
        fillContent(devSelf, SELF_DEV);
        fillContent(bizSelf, SELF_BIZ);
        repo.save(devSelf);
        repo.save(bizSelf);
        repo.save(new TaskRecord(3L, LIVE_DEV, "주문 처리 장애 원인 분석", "DEV", 60, TaskRecord.Usage.LIVE, TaskRecord.Status.OPEN, 1L, 1L, at));
        repo.save(new TaskRecord(4L, LIVE_BIZ, "신규 저가 요금제 출시 기획안", "BIZ", 60, TaskRecord.Usage.LIVE, TaskRecord.Status.OPEN, 2L, 1L, at));
    }

    /** 과제 행 + 파일 + 후속 질문 */
    public static void seed(TaskRecordRepository repo, TaskFileRecordRepository files, TaskQuestionRecordRepository questions) {
        seed(repo);
        seedContent(files, questions, 1L, SELF_DEV);
        seedContent(files, questions, 2L, SELF_BIZ);
    }

    /** 이 task_id 의 파일·후속 질문을 픽스처 폴더 내용으로 다시 넣는다 */
    public static void seedContent(TaskFileRecordRepository files, TaskQuestionRecordRepository questions, long taskId, String code) {
        files.deleteAll(files.findAll().stream().filter(f -> f.getTaskId() == taskId).toList());
        questions.deleteAll(questions.findAll().stream().filter(q -> q.getTaskId() == taskId).toList());
        for (var e : fixtureFiles(code, "files").entrySet()) {
            files.save(new TaskFileRecord(taskId, e.getKey(), e.getValue(), "text/plain", e.getValue().getBytes(StandardCharsets.UTF_8).length, TaskFileRecord.VISIBLE, false));
        }
        for (var e : fixtureFiles(code, "hidden").entrySet()) {
            files.save(new TaskFileRecord(taskId, e.getKey(), e.getValue(), "text/plain", e.getValue().getBytes(StandardCharsets.UTF_8).length, TaskFileRecord.HIDDEN, true));
        }
        List<String> followUps = fixture(code).followUps();
        for (int i = 0; i < followUps.size(); i++) {
            questions.save(new TaskQuestionRecord(taskId, TaskQuestionRecord.FOLLOWUP, "ESSAY", i + 1, followUps.get(i)));
        }
    }

    /** 옛 task.json 그대로 읽은 과제 정의 (비교 기준) */
    public static TaskDef fixture(String code) {
        try {
            return JSON.readValue(dir(code).resolve("task.json").toFile(), TaskDef.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 옛 폴더(files 또는 hidden)의 파일들: 상대 경로 → 내용. 줄바꿈은 LF 로 맞춘다(checkout 설정에 따라 CRLF 일 수 있음) */
    public static Map<String, String> fixtureFiles(String code, String folder) {
        Path base = dir(code).resolve(folder);
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static void fillContent(TaskRecord t, String code) {
        TaskDef d = fixture(code);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("openFile", d.openFile());
        settings.put("editableFile", d.editableFile());
        settings.put("requiredHeadings", d.requiredHeadings());
        settings.put("d4Minutes", d.d4Minutes());
        try {
            t.setContent(String.join("\n", d.description()), String.join("\n", d.requirements()), JSON.writeValueAsString(settings));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path dir(String code) {
        try {
            return Path.of(TestTasks.class.getResource("/task-fixtures/" + code).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
