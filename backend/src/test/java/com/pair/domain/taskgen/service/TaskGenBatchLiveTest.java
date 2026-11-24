package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.exception.TooManyJobsException;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.infra.storage.TaskFileStorage;

/**
 * 실제 AI 로 과제 6개(DEV/BIZ × 20·40·60분, 주제는 매번 추천에서)를 3개씩 동시에 만들고 내용을 build/taskgen-batch/ 에 내보낸다 (H2, 비용 발생).
 * PAIR_TASKGEN_LIVE=1 일 때만 돈다. 데이터 폴더도 build/taskgen-batch/data 에 고정해 PDF 가 남는다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_TASKGEN_LIVE", matches = "1")
@Tag("slow")
class TaskGenBatchLiveTest {

    static final int CONCURRENT = 3;
    static final long ADMIN_ID = 1L;
    static final Path OUT = Path.of("build", "taskgen-batch").toAbsolutePath();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> OUT.resolve("data").toString());
        r.add("pair.taskgen.file-storage", () -> "local");
        r.add("pair.taskgen.max-concurrent", () -> String.valueOf(CONCURRENT));
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @MockitoBean JavaMailSender mail;
    @Autowired TaskGenJobService jobs;
    @Autowired TaskSuggestionService suggestions;
    @Autowired GeneratedTaskRepository writer;
    @Autowired TaskRepository records;
    @Autowired TaskFileRepository files;
    @Autowired TaskFileStorage storage;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 한 번에 만들 과제 수. 6 = DEV/BIZ × 20·40·60분 1개씩. 12로 올리면 같은 조합을 한 번 더(6의 배수만). 환경변수 PAIR_TASKGEN_COUNT=12 (Gradle 은 -D 를 테스트 JVM 에 넘기지 않는다) */
    static final int COUNT = Integer.parseInt(System.getenv().getOrDefault("PAIR_TASKGEN_COUNT", "6"));
    static final int[] MINUTES = {20, 40, 60};

    /** 매번 새 주제: 과제 추천(TaskSuggestionService, 주제 없음 모드)에서 직군마다 받는다. 이미 있는 과제·앞서 받은 후보와는 겹치지 않는다 */
    private List<TaskGenRequest> pickForms() {
        if (COUNT < 6 || COUNT % 6 != 0) throw new IllegalArgumentException("count 는 6의 배수여야 합니다: " + COUNT);
        List<TaskGenRequest> forms = new ArrayList<>();
        for (String track : List.of("DEV", "BIZ")) {
            List<TaskSuggestionService.Suggestion> picked = new ArrayList<>();
            for (int tries = 0; picked.size() < COUNT / 2 && tries < 5; tries++) {
                List<String> exclude = picked.stream().map(TaskSuggestionService.Suggestion::situation).toList();
                var res = suggestions.suggest(new TaskSuggestionService.Request(track, "", exclude), ADMIN_ID);
                for (var sg : res.suggestions()) if (picked.size() < COUNT / 2) picked.add(sg);
            }
            if (picked.size() < COUNT / 2) throw new IllegalStateException(track + " 추천을 " + COUNT / 2 + "개 받지 못했습니다");
            for (int i = 0; i < picked.size(); i++) {
                var sg = picked.get(i);
                forms.add(new TaskGenRequest(track, sg.situation(), sg.trap(), "SELF", MINUTES[i % MINUTES.length], sg.competencies()).validated());
            }
        }
        return forms;
    }

    @Test
    void generateBatch() throws Exception {
        List<TaskGenRequest> forms = pickForms();

        clean(OUT); // 이전 실행·시도의 파일(옛 숨은 테스트, 안 쓰는 PDF·CSV)이 폴더에 섞이지 않게 비운다
        Files.createDirectories(OUT);
        long total = System.nanoTime();
        // 실제 서비스 경로(TaskGenJobService.start → status 폴링). 자리(max-concurrent=3)가 나야 다음 작업을 넣는다
        String[] lines = new String[forms.size()];
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < forms.size(); i++) {
                int idx = i;
                futures.add(pool.submit(() -> lines[idx] = runOne(idx, forms.get(idx))));
            }
            for (Future<?> fu : futures) fu.get();
        }
        long sec = Math.round((System.nanoTime() - total) / 1e9);
        StringBuilder md = new StringBuilder("# 과제 생성 배치 결과\n\n");
        md.append("동시 실행 ").append(CONCURRENT).append("개 (TaskGenJobService 경로)\n\n");
        for (String l : lines) md.append(l);
        md.append("\n총 ").append(sec).append("초 (작업별 시간은 각 줄의 '초', 시작 후 완료까지)\n");
        Files.writeString(OUT.resolve("summary.md"), md.toString());
    }

    /** 한 과제를 만든다. 한 과제가 실패해도 예외를 밖으로 내보내지 않고 summary 한 줄로 남긴다 */
    private String runOne(int i, TaskGenRequest form) {
        String nn = String.format("%02d", i + 1);
        long t = System.nanoTime();
        String code = null;
        try {
            String jobId = startWhenFree(form, nn);
            t = System.nanoTime(); // 자리가 나서 실제로 시작한 때부터 잰다
            TaskGenJobService.JobStatus st;
            String lastStep = "";
            while ((st = jobs.status(jobId)).status() == TaskGenJobService.State.RUNNING) {
                if (!st.step().equals(lastStep)) {
                    lastStep = st.step();
                    System.out.println("[batch " + nn + "] " + lastStep);
                }
                Thread.sleep(2000);
            }
            long sec = Math.round((System.nanoTime() - t) / 1e9);
            if (st.status() == TaskGenJobService.State.FAILED) throw new IllegalStateException(st.error());
            code = st.taskCode();
            export(OUT.resolve(nn + "-" + code), form, code);

            String tokens = "-";
            int attempts = 0;
            try {
                JsonNode rep = json.readTree(writer.report(code));
                tokens = rep.path("inputTokens").asLong() + "/" + rep.path("outputTokens").asLong();
                attempts = rep.path("attempts").asInt();
            } catch (Exception ignore) { }
            System.out.println("[batch " + nn + "] 완료 code=" + code + " passed=" + st.passed() + " " + sec + "초");
            return "- " + nn + " | " + form.track() + " | " + form.minutes() + "분 | " + form.usage() + " | " + code
                    + " | passed=" + st.passed() + " | attempts=" + attempts + " | " + sec + "초 | tokens(in/out)=" + tokens
                    + " | cost=미제공 | fail=" + names(st.checks(), false) + " | warn=" + names(st.checks(), true) + "\n";
        } catch (Throwable e) {
            long sec = Math.round((System.nanoTime() - t) / 1e9);
            e.printStackTrace();
            try {
                Path dir = OUT.resolve(nn + "-" + (code == null ? "error" : code));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("request.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(form));
                Files.writeString(dir.resolve("error.txt"), String.valueOf(e));
            } catch (Exception ignore) { }
            return "- " + nn + " | " + form.track() + " | " + form.minutes() + "분 | " + form.usage() + " | 예외 | " + sec
                    + "초 | " + String.valueOf(e).replace('\n', ' ') + "\n";
        }
    }

    /** 동시 실행 상한(429)에 걸리면 자리가 날 때까지 기다렸다가 다시 넣는다 */
    private String startWhenFree(TaskGenRequest form, String nn) throws InterruptedException {
        while (true) {
            try {
                return jobs.start(form, ADMIN_ID);
            } catch (TooManyJobsException full) {
                Thread.sleep(3000);
            }
        }
    }

    private static String names(List<GenCheckResultDto> checks, boolean warn) {
        return checks.stream()
                .filter(c -> !c.ok() && (warn == GenCheckResultDto.WARN.equals(c.level())))
                .map(GenCheckResultDto::name).collect(Collectors.joining(","));
    }

    /** 폴더와 안의 모든 파일을 지운다(없으면 아무것도 안 함) */
    static void clean(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private void export(Path dir, TaskGenRequest form, String code) throws Exception {
        clean(dir); // 같은 코드로 다시 내보낼 때 옛 파일이 남지 않게
        Files.createDirectories(dir);
        var pretty = json.writerWithDefaultPrettyPrinter();
        Files.writeString(dir.resolve("request.json"), pretty.writeValueAsString(form));
        Files.writeString(dir.resolve("report.json"), pretty.writeValueAsString(json.readTree(writer.report(code))));
        Task task = records.findByCode(code).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", task.getCode());
        m.put("title", task.getTitle());
        m.put("track", task.getTrack());
        m.put("difficulty", task.getDifficulty());
        m.put("timeLimitMin", task.getTimeLimitMin());
        m.put("usage", task.getUsage());
        m.put("category", task.getCategory());
        m.put("summary", task.getSummary());
        m.put("description", task.getDescription());
        m.put("requirements", task.getRequirements());
        m.put("settings", task.getSettings() == null ? null : json.readTree(task.getSettings()));
        Files.writeString(dir.resolve("task.json"), pretty.writeValueAsString(m));

        List<TaskFile> mine = new ArrayList<>(files.findAll().stream().filter(x -> task.getId().equals(x.getTaskId())).toList());
        Path fileRoot = dir.resolve("files").normalize();
        for (TaskFile tf : mine) {
            Path p = fileRoot.resolve(tf.getPath()).normalize();
            if (!p.startsWith(fileRoot)) continue;
            Files.createDirectories(p.getParent());
            if (tf.getContent() != null) {
                Files.write(p, tf.getContent().getBytes(StandardCharsets.UTF_8));
            } else if (tf.getStorageKey() != null) {
                byte[] b = storage.get(tf.getStorageKey()).orElse(null);
                if (b != null) Files.write(p, b);
            }
        }
        List<Map<String, Object>> meta = mine.stream().map(tf -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", tf.getPath());
            e.put("visibility", tf.getVisibility());
            e.put("mimeType", tf.getMimeType());
            e.put("sizeBytes", tf.getSizeBytes());
            e.put("storageKey", tf.getStorageKey());
            return e;
        }).toList();
        Files.writeString(dir.resolve("files.json"), pretty.writeValueAsString(meta));
        Files.writeString(dir.resolve("questions.json"), pretty.writeValueAsString(questions(task.getId(), code)));
    }

    /** 문항(객관식·단답형·후속 질문) 전체. calc 와 흔한 오답은 DB 에 없어 report.json 의 details.questionNotes 에서 가져온다 */
    private List<Map<String, Object>> questions(Long taskId, String code) throws Exception {
        Map<Integer, JsonNode> notes = new LinkedHashMap<>();
        try {
            JsonNode arr = json.readTree(writer.report(code)).path("details").path("questionNotes");
            for (JsonNode n : arr) notes.put(n.path("seq").asInt(), n);
        } catch (Exception ignore) { }
        List<Map<String, Object>> out = new ArrayList<>();
        var rows = jdbc.queryForList("select question_id, stage, question_type, seq, content, points from task_questions"
                + " where task_id = ? order by stage desc, seq", taskId);
        for (var r : rows) {
            Long qid = ((Number) r.get("question_id")).longValue();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("stage", r.get("stage"));
            q.put("type", r.get("question_type"));
            q.put("seq", r.get("seq"));
            q.put("content", r.get("content"));
            q.put("points", r.get("points"));
            q.put("choices", jdbc.queryForList("select seq, content, is_correct as correct from question_choices where question_id = ? order by seq", qid));
            q.put("accepted", jdbc.queryForList("select accepted from short_answer_keys where question_id = ?", qid).stream().map(m -> m.get("accepted")).toList());
            q.put("sources", jdbc.queryForList("select f.path as path, s.pages as pages from question_sources s"
                    + " join task_files f on f.task_file_id = s.task_file_id where s.question_id = ?", qid));
            JsonNode note = "TASK".equals(r.get("stage")) ? notes.get(((Number) r.get("seq")).intValue()) : null;
            q.put("calc", note == null ? null : note.path("calc").asText(null));
            q.put("commonWrongAnswers", note == null ? null : json.treeToValue(note.path("commonWrongAnswers"), Object.class));
            out.add(q);
        }
        return out;
    }
}
