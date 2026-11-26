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

    static final int CONCURRENT = Integer.parseInt(System.getenv().getOrDefault("PAIR_TASKGEN_PARALLEL", "10")); // 라이브 배치만: 한 번에 도는 작업 수(운영 max-concurrent 와 무관)
    static final long ADMIN_ID = 1L;
    /** 과제마다 report 의 timing(모델 호출 초, 단계별 초, 코드 실행 대기·실행 초). summary 의 평균 한 줄에 쓴다 */
    static final List<JsonNode> TIMINGS = java.util.Collections.synchronizedList(new ArrayList<>());
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

    /**
     * 한 번에 만들 과제 수(1~10, 기본 10: 생성 동시 상한과 같다). 과제 번호 순서대로 DEV, BIZ 를 번갈아(홀수면 DEV 가 하나 더 많다) 배정하고,
     * 제한 시간은 20·40·60분을 번호 순서대로 돌아가며 배정한다. 환경변수 PAIR_TASKGEN_COUNT=8 (Gradle 은 -D 를 테스트 JVM 에 넘기지 않는다)
     */
    static final int COUNT = Integer.parseInt(System.getenv().getOrDefault("PAIR_TASKGEN_COUNT", "10"));
    /** 실행 방식: PAIR_TASKGEN_MODE=BATCH 면 배치(비용 약 절반, 느림), 기본 NOW. 배치 마감은 PAIR_TASKGEN_DEADLINE_HOURS(1·3·6, 기본 3). 배치는 실제 돈이 든다 */
    static final String MODE = System.getenv().getOrDefault("PAIR_TASKGEN_MODE", "NOW").strip().toUpperCase();
    static final int DEADLINE_HOURS = Integer.parseInt(System.getenv().getOrDefault("PAIR_TASKGEN_DEADLINE_HOURS", "3"));
    static final int[] MINUTES = {20, 40, 60};
    /** 구성 고르기: PAIR_TASKGEN_TRACKS=DEV,BIZ PAIR_TASKGEN_MINUTES=40 이면 직군마다 40분 과제 1개씩. 둘 다 없으면 위 기본(COUNT 개) */
    static final String TRACKS_ENV = System.getenv("PAIR_TASKGEN_TRACKS"), MINUTES_ENV = System.getenv("PAIR_TASKGEN_MINUTES");

    /** 직군과 제한 시간 목록(직군마다 시간마다 1개). 환경변수를 안 쓰면 null */
    static List<String> customTracks() {
        return TRACKS_ENV == null || TRACKS_ENV.isBlank() && (MINUTES_ENV == null || MINUTES_ENV.isBlank()) ? null
                : List.of((TRACKS_ENV == null || TRACKS_ENV.isBlank() ? "DEV,BIZ" : TRACKS_ENV).trim().split("\\s*,\\s*"));
    }

    /** DEV, BIZ 를 번갈아 COUNT 개(홀수면 DEV 가 하나 더), 시간은 20·40·60 순환. 주제는 직군마다 한 번에 필요한 만큼 추천받는다 */
    private List<TaskGenRequest> interleaved() {
        int dev = (COUNT + 1) / 2, biz = COUNT / 2;
        Map<String, List<TaskSuggestionService.Suggestion>> picks = new LinkedHashMap<>();
        for (var e : Map.of("DEV", dev, "BIZ", biz).entrySet()) {
            String track = e.getKey();
            int need = e.getValue();
            List<TaskSuggestionService.Suggestion> picked = new ArrayList<>();
            for (int tries = 0; picked.size() < need && tries < 5; tries++) {
                List<String> exclude = picked.stream().map(TaskSuggestionService.Suggestion::situation).toList();
                TaskSuggestionService.Result res;
                try {
                    res = suggestions.suggest(new TaskSuggestionService.Request(track, "", exclude), ADMIN_ID);
                } catch (com.pair.infra.ai.AiUnavailableException cut) {
                    continue; // 추천 한 번이 출력 한도에서 끊겨도(g5 첫 실행) 배치 전체를 멈추지 않고 다시 묻는다
                }
                for (var sg : res.suggestions()) if (picked.size() < need) picked.add(sg);
            }
            if (picked.size() < need) throw new IllegalStateException(track + " 추천을 " + need + "개 받지 못했습니다");
            picks.put(track, picked);
        }
        List<TaskGenRequest> forms = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            String track = i % 2 == 0 ? "DEV" : "BIZ";
            var sg = picks.get(track).get(i / 2);
            forms.add(new TaskGenRequest(track, sg.situation(), sg.trap(), "SELF", MINUTES[i % MINUTES.length], sg.competencies()).validated());
        }
        return forms;
    }

    /** 매번 새 주제: 과제 추천(TaskSuggestionService, 주제 없음 모드)에서 직군마다 받는다. 이미 있는 과제·앞서 받은 후보와는 겹치지 않는다 */
    private List<TaskGenRequest> pickForms() {
        List<String> tracks = customTracks();
        int[] minutes = MINUTES;
        int per;
        if (tracks == null) {
            if (COUNT < 1 || COUNT > 10) throw new IllegalArgumentException("count 는 1~10 이어야 합니다(생성 동시 상한이 10): " + COUNT);
            return interleaved();
        } else {
            if (MINUTES_ENV != null && !MINUTES_ENV.isBlank()) minutes = java.util.Arrays.stream(MINUTES_ENV.trim().split("\\s*,\\s*")).mapToInt(Integer::parseInt).toArray();
            per = minutes.length; // 직군마다 시간마다 1개
            if (MINUTES_ENV == null || MINUTES_ENV.isBlank()) { // PAIR_TASKGEN_TRACKS=DEV 만 주면 그 직군만 COUNT 개, 시간은 20·40·60 순환
                if (COUNT < 1 || COUNT > 10) throw new IllegalArgumentException("count 는 1~10 이어야 합니다(생성 동시 상한이 10): " + COUNT);
                per = Math.max(1, COUNT / tracks.size());
            }
        }
        List<TaskGenRequest> forms = new ArrayList<>();
        for (String track : tracks) {
            List<TaskSuggestionService.Suggestion> picked = new ArrayList<>();
            for (int tries = 0; picked.size() < per && tries < 8; tries++) {
                List<String> exclude = picked.stream().map(TaskSuggestionService.Suggestion::situation).toList();
                TaskSuggestionService.Result res;
                try {
                    res = suggestions.suggest(new TaskSuggestionService.Request(track, "", exclude), ADMIN_ID);
                } catch (com.pair.infra.ai.AiUnavailableException cut) {
                    continue; // 추천 한 번이 끊겨도 배치를 멈추지 않는다
                }
                for (var sg : res.suggestions()) if (picked.size() < per) picked.add(sg);
            }
            if (picked.size() < per) throw new IllegalStateException(track + " 추천을 " + per + "개 받지 못했습니다");
            for (int i = 0; i < picked.size(); i++) {
                var sg = picked.get(i);
                forms.add(new TaskGenRequest(track, sg.situation(), sg.trap(), "SELF", minutes[i % minutes.length], sg.competencies()).validated());
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
        double sum = 0;
        for (String l : lines) {
            var m = java.util.regex.Pattern.compile("cost=\\$([0-9.]+)").matcher(l == null ? "" : l);
            if (m.find()) sum += Double.parseDouble(m.group(1));
        }
        md.append(String.format(java.util.Locale.ROOT, "\n비용 합계 $%.3f (각 줄의 attempts=시도 수, cost=토큰×가격)\n", sum));
        md.append(soloLine(lines)).append('\n');
        md.append(timingLine()).append('\n');
        md.append("\n총 ").append(sec).append("초 (작업별 시간은 각 줄의 '초', 시작 후 완료까지)\n");
        Files.writeString(OUT.resolve("summary.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** 단계별 평균 시간(과제 1개당 초): 모델 호출 합, 단계별 모델 호출, 코드 실행 줄서기, 코드 실행 */
    static String timingLine() {
        List<JsonNode> ts;
        synchronized (TIMINGS) { ts = new ArrayList<>(TIMINGS); }
        if (ts.isEmpty()) return "\n단계별 평균 시간: 기록 없음";
        int n = ts.size();
        double model = 0, wait = 0, run = 0, runs = 0;
        Map<String, Double> stage = new java.util.TreeMap<>();
        for (JsonNode t : ts) {
            model += t.path("modelSeconds").asDouble();
            wait += t.path("runWaitSeconds").asDouble();
            run += t.path("runSeconds").asDouble();
            runs += t.path("runCount").asDouble();
            t.path("modelSecondsByStage").fields().forEachRemaining(e -> stage.merge(e.getKey(), e.getValue().asDouble(), Double::sum));
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "\n단계별 평균 시간(과제 %d개, 과제당 초): 모델 호출 %.0f, 코드 실행 줄서기 %.1f, 코드 실행 %.1f (실행 %.1f번)", n, model / n, wait / n, run / n, runs / n));
        final int cnt = n;
        sb.append(" | 모델 단계별: ").append(stage.entrySet().stream()
                .map(e -> String.format(java.util.Locale.ROOT, "%s %.0f", e.getKey(), e.getValue() / cnt)).collect(Collectors.joining(", ")));
        return sb.toString();
    }

    /** 난이도 목표(AI 단독 풀이가 결함을 놓친 비율 50% 이상, 참고)를 보는 한 줄. 놓침 / 돌린 것(놓침+잡음), 안 돌았거나 확인 못 한 것은 따로 센다 */
    static String soloLine(String[] lines) {
        int missed = 0, caught = 0, notRun = 0, invalid = 0;
        for (String l : lines) {
            var m = java.util.regex.Pattern.compile("\\| solo=(\\S+)").matcher(l == null ? "" : l);
            String st = m.find() ? m.group(1) : "";
            if (st.equals("missed")) missed++;
            else if (st.equals("caught")) caught++;
            else if (st.equals("invalid")) invalid++; // 컴파일 실패·결함과 상관없는 테스트 실패: 분모에서 뺀다
            else if (l != null && l.contains("| passed=")) notRun++; // 건너뜀·확인 못 함·알 수 없음
        }
        int ran = missed + caught;
        return String.format(java.util.Locale.ROOT, "\nAI 단독 풀이 놓침 %d/%d (%s), 무효(컴파일 실패 등) %d개, 안 돌았거나 확인 못 함 %d개 (목표 50%% 이상, 참고)", missed, ran,
                ran == 0 ? "-" : Math.round(missed * 100.0 / ran) + "%", invalid, notRun);
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
            while ((st = jobs.status(jobId)).status() == TaskGenJobService.State.RUNNING || st.status() == TaskGenJobService.State.QUEUED) {
                jobs.tick(); // 워커가 꺼진 테스트 설정에서도 대기 작업을 줍는다
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

            String tokens = "-", cost = "미제공", solo = "알 수 없음";
            int attempts = 0;
            try {
                JsonNode rep = json.readTree(writer.report(code));
                tokens = rep.path("inputTokens").asLong() + "/" + rep.path("outputTokens").asLong();
                attempts = rep.path("attempts").asInt();
                solo = rep.path("details").path("solo").path("status").asText("알 수 없음");
                if (rep.path("details").has("timing")) TIMINGS.add(rep.path("details").path("timing"));
                JsonNode c = rep.path("details").path("cost");
                if (!c.isMissingNode()) cost = String.format(java.util.Locale.ROOT, "$%.3f%s", c.path("totalUsd").asDouble(), c.path("stoppedByCap").asBoolean() ? "(비용 상한)" : "");
            } catch (Exception ignore) { }
            System.out.println("[batch " + nn + "] 완료 code=" + code + " passed=" + st.passed() + " " + sec + "초");
            return "- " + nn + " | " + form.track() + " | " + form.minutes() + "분 | " + form.usage() + " | " + code
                    + " | passed=" + st.passed() + " | attempts=" + attempts + " | " + sec + "초 | tokens(in/out)=" + tokens
                    + " | cost=" + cost + " | solo=" + solo + " | fail=" + names(st.checks(), false) + " | warn=" + names(st.checks(), true) + "\n";
        } catch (Throwable e) {
            long sec = Math.round((System.nanoTime() - t) / 1e9);
            e.printStackTrace();
            try {
                Path dir = OUT.resolve(nn + "-" + (code == null ? "error" : code));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("request.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(form), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("error.txt"), String.valueOf(e), StandardCharsets.UTF_8);
            } catch (Exception ignore) { }
            return "- " + nn + " | " + form.track() + " | " + form.minutes() + "분 | " + form.usage() + " | 예외 | " + sec
                    + "초 | " + String.valueOf(e).replace('\n', ' ') + "\n";
        }
    }

    /** 대기열에 넣는다(상한을 넘으면 서비스가 알아서 기다리게 한다) */
    private String startWhenFree(TaskGenRequest form, String nn) {
        return jobs.start(form, ADMIN_ID, MODE, MODE.equals("BATCH") ? DEADLINE_HOURS : null, null);
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
        Files.writeString(dir.resolve("request.json"), pretty.writeValueAsString(form), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("report.json"), pretty.writeValueAsString(json.readTree(writer.report(code))), StandardCharsets.UTF_8);
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
        Files.writeString(dir.resolve("task.json"), pretty.writeValueAsString(m), StandardCharsets.UTF_8);

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
        Files.writeString(dir.resolve("files.json"), pretty.writeValueAsString(meta), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("questions.json"), pretty.writeValueAsString(questions(task.getId(), code)), StandardCharsets.UTF_8);
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
