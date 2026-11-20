package com.pair.taskgen;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.task.TaskFileRecord;
import com.pair.task.TaskFileRecordRepository;
import com.pair.task.TaskQuestionRecord;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskRecord;
import com.pair.task.TaskRecordRepository;

/**
 * 생성한 과제를 DB 에 한 트랜잭션으로 저장한다 (tasks, task_files, task_questions).
 * 중간에 실패하면 과제가 반쯤 남지 않는다. 저장된 과제는 검수 전(HIDDEN)이다.
 *
 * 파일 경로 규칙은 order-deadlock 시드(V6)와 같다.
 * - 시작 코드, 공개 테스트: 그대로(src/…, test/…), VISIBLE
 * - 숨은 테스트: 앞의 test/ 를 뗀 경로(shop/HiddenXTest.java), HIDDEN. 채점이 hidden/ 을 붙여 컴파일한다
 * - 정답 설명: answer.md, HIDDEN
 * - 확인 결과 _gen/report.json, 정답 코드 _gen/solution/…, HIDDEN. 정답 코드는 채점 때 .java 로 컴파일되면 클래스가
 *   겹치므로 끝에 .txt 를 붙여 둔다
 */
@Component
public class GeneratedTaskWriter {

    public static final String REPORT_PATH = "_gen/report.json";
    public static final String SOLUTION_DIR = "_gen/solution/";

    /** DEV 과제 공통 후속 질문 (order-deadlock 시드와 같은 문구) */
    public static final List<String> DEV_FOLLOW_UPS = List.of(
            "AI 답변 중 그대로 믿지 않고 직접 확인한 것은 무엇이고, 어떻게 확인했나요? (예: 테스트 실행, 로그 대조)",
            "AI가 제안한 내용 중 채택하지 않은 것이 있다면 무엇이고, 이유는 무엇인가요?",
            "동료가 같은 결과를 얻으려면 AI에게 어떤 지시를 어떤 순서로 주면 되나요?");

    private static final List<Integer> D4_MINUTES = List.of(20, 30, 40, 50);

    private final TaskRecordRepository records;
    private final TaskFileRecordRepository files;
    private final TaskQuestionRecordRepository questions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public GeneratedTaskWriter(TaskRecordRepository records, TaskFileRecordRepository files,
            TaskQuestionRecordRepository questions, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.records = records;
        this.files = files;
        this.questions = questions;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** 저장하고 과제 code 를 돌려준다. code 가 겹치면 -2, -3 … 을 붙인다 */
    @Transactional
    public String saveDev(TaskGenForm form, DevDraft d, TaskGenerator.GenReport report, long adminId) {
        String code = uniqueCode(d.code(), d.title());
        Instant now = clock.instant();
        long id = nextTaskId();

        TaskRecord t = new TaskRecord(id, code, cut(d.title(), 200), "DEV", form.minutes(), form.usageValue(),
                TaskRecord.Status.HIDDEN, null, adminId, now);
        t.setCard(cut(orElse(d.summary(), d.title()), 200), cut(orElse(d.category(), "기능 구현"), 30), form.difficulty());
        t.setContent(String.join("\n", d.description()), String.join("\n", d.requirements()), settings(d));
        records.save(t);

        List<TaskFileRecord> rows = new ArrayList<>();
        for (var f : d.files()) rows.add(file(id, f.path(), f.content(), TaskFileRecord.VISIBLE, false));
        for (var f : d.hiddenTests()) rows.add(file(id, f.path().substring("test/".length()), f.content(), TaskFileRecord.HIDDEN, true));
        rows.add(file(id, "answer.md", d.answer(), TaskFileRecord.HIDDEN, true));
        for (var f : d.solution()) rows.add(file(id, SOLUTION_DIR + f.path() + ".txt", f.content(), TaskFileRecord.HIDDEN, true));
        rows.add(file(id, REPORT_PATH, toJson(report), TaskFileRecord.HIDDEN, true));
        files.saveAll(rows);

        for (int i = 0; i < DEV_FOLLOW_UPS.size(); i++) {
            questions.save(new TaskQuestionRecord(id, TaskQuestionRecord.FOLLOWUP, "ESSAY", i + 1, DEV_FOLLOW_UPS.get(i)));
        }
        return code;
    }

    /** 과제 콘솔이 보여 줄 확인 결과(report.json 내용). 없으면 NoSuchElementException(404) */
    @Transactional(readOnly = true)
    public String report(String code) {
        TaskRecord t = records.findByCode(code).orElseThrow(() -> new NoSuchElementException("없는 과제입니다: " + code));
        Optional<TaskFileRecord> f = files.findByTaskIdAndPath(t.getId(), REPORT_PATH);
        return f.map(TaskFileRecord::getContent).orElseThrow(() -> new NoSuchElementException("AI 생성 확인 결과가 없는 과제입니다: " + code));
    }

    private String uniqueCode(String wanted, String title) {
        String base = slug(wanted);
        if (base.isEmpty()) base = slug(title);
        if (base.isEmpty()) base = "dev-task";
        String code = base;
        for (int n = 2; records.findByCode(code).isPresent(); n++) code = base + "-" + n;
        return code;
    }

    /** 영문 소문자·숫자·하이픈만 남긴다 */
    static String slug(String s) {
        if (s == null) return "";
        String v = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return v.length() > 40 ? v.substring(0, 40).replaceAll("-+$", "") : v;
    }

    private String settings(DevDraft d) {
        String open = d.files().stream().map(DevDraft.FileDraft::path).filter(p -> p.startsWith("src/")).findFirst().orElse(null);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("openFile", open);
        s.put("editableFile", null);
        s.put("requiredHeadings", List.of());
        s.put("d4Minutes", D4_MINUTES);
        return toJson(s);
    }

    /** tasks.task_id 는 PostgreSQL 에서는 시퀀스, 테스트 DB(H2)에서는 최댓값+1 로 정한다 */
    private long nextTaskId() {
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        Long id = "PostgreSQL".equals(product)
                ? jdbc.queryForObject("select nextval(pg_get_serial_sequence('tasks', 'task_id'))", Long.class)
                : jdbc.queryForObject("select coalesce(max(task_id), 0) + 1 from tasks", Long.class);
        return id == null ? 1 : id;
    }

    private static TaskFileRecord file(long taskId, String path, String content, String visibility, boolean readOnly) {
        return new TaskFileRecord(taskId, path, content, mime(path), content.getBytes(StandardCharsets.UTF_8).length, visibility, readOnly);
    }

    private static String mime(String path) {
        if (path.endsWith(".java")) return "text/x-java-source";
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 으로 바꾸지 못했습니다.", e);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String orElse(String s, String other) {
        return s == null || s.isBlank() ? other : s.strip();
    }
}
