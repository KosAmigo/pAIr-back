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
 * 실제 AI 로 과제 10개를 3개씩 동시에 만들고 내용을 build/taskgen-batch/ 에 내보낸다 (H2, 비용 발생).
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
    @Autowired GeneratedTaskRepository writer;
    @Autowired TaskRepository records;
    @Autowired TaskFileRepository files;
    @Autowired TaskFileStorage storage;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static TaskGenRequest f(String track, String topic, String trap, String usage, int min) {
        return new TaskGenRequest(track, topic, trap, usage, min).validated();
    }

    @Test
    void generateTen() throws Exception {
        List<TaskGenRequest> forms = List.of(
                f("DEV", "동시 주문 재고 차감", "두 주문이 동시에 마지막 재고를 차감해 재고가 음수가 됨", "LIVE", 60),
                f("DEV", "포인트 적립 계산", "할인 전 금액에 적립률을 곱한 뒤 절사해 적립액이 1원씩 어긋남", "SELF", 20),
                f("DEV", "게시글 목록 페이지 나누기", "전체 개수가 페이지 크기의 배수일 때 마지막 페이지 수가 하나 많음", "SELF", 20),
                f("DEV", "회의실 예약 겹침 확인", "앞 예약이 끝나는 시각에 시작하는 예약을 겹친다고 거절함", "LIVE", 40),
                f("DEV", "무료 배송 기준 판정", "쿠폰 할인 후 금액이 아니라 할인 전 금액으로 무료 배송을 판단함", "SELF", 40),
                f("BIZ", "구독 해지율 분석", "신규 가입 급증으로 분모가 커져 해지율이 좋아진 것처럼 보임", "SELF", 60),
                f("BIZ", "신규 매장 입지 선정", "유동 인구 자료가 평일에만 측정되어 주말 상권을 놓침", "LIVE", 40),
                f("BIZ", "마케팅 채널 예산 재배분", "마지막 클릭 기여만 보고 검색 광고 성과를 과대평가함", "LIVE", 60),
                f("BIZ", "고객센터 상담 인력 배치", "하루 평균 문의량만 보고 점심 직후 몰리는 시간을 무시함", "SELF", 20),
                f("BIZ", "가격 인상 효과 판단", "같은 기간 성수기 효과를 가격 인상 효과로 착각함", "SELF", 40));

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

    private void export(Path dir, TaskGenRequest form, String code) throws Exception {
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
