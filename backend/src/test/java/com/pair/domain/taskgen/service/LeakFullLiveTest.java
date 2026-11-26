package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 답 노출 관문(leak.md)을 g9-biz 과제 전체 맥락으로 다시 돌려 본다(2026-11-26: 짧은 입력에선 통과한 회사 정의 기록을 전체 맥락에선 1차가 잡음).
 * taskgen-runs/2026-11-26-g9-biz 내보내기(설명·요구사항·PDF 쪽 글·CSV·문항·숨은 결함)로 관문 입력을 TaskReviewService.bizInput 과 같은 모양으로 다시 만든다.
 * PAIR_LEAK_FULL=1 일 때만 돈다(비용 발생). 결과는 build/leak-full.txt. 판정은 사람이 본다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_LEAK_FULL", matches = "1")
@Tag("slow")
class LeakFullLiveTest {

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean org.springframework.mail.javamail.JavaMailSender mail;
    @Autowired TaskGenAiService ai;
    @Autowired TaskGenProperties props;

    static final Path RUN = Path.of("..", "..", "taskgen-runs", "2026-11-26-g9-biz");
    /** 과제 번호 → 기대(true = 잡아야 함). 02·08·09 는 회사 정의 기록(통과), 05·07 은 문항이 계산법·기준을 정함(잡음) */
    static final List<Object[]> CASES = List.of(new Object[] {"02", false}, new Object[] {"08", false}, new Object[] {"09", false}, new Object[] {"05", true},
            new Object[] {"07", true});

    static String input(Path dir) throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode task = json.readTree(dir.resolve("task.json").toFile());
        JsonNode report = json.readTree(dir.resolve("report.json").toFile());
        JsonNode questions = json.readTree(dir.resolve("questions.json").toFile());
        StringBuilder sb = new StringBuilder("직군: BIZ, 제한 시간: ").append(task.path("timeLimitMin").asInt()).append("분\n[숨은 결함(출제자만 아는 정보)]\n");
        for (JsonNode h : report.path("details").path("hiddenFlaws")) sb.append("- ").append(h.path("description").asText()).append(" (위치: ").append(h.path("verifyWith").asText()).append(")\n");
        sb.append("[설명]\n").append(task.path("description").asText()).append("\n[요구사항]\n").append(task.path("requirements").asText()).append('\n');
        File[] files = dir.resolve("files").resolve("자료").toFile().listFiles();
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".pdf")) continue;
            sb.append("\n[문서 자료/").append(f.getName()).append("]\n");
            try (PDDocument pdf = Loader.loadPDF(f)) {
                for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                    PDFTextStripper s = new PDFTextStripper();
                    s.setStartPage(p);
                    s.setEndPage(p);
                    sb.append('(').append(p).append("쪽) ").append(s.getText(pdf).strip()).append('\n');
                }
            }
        }
        for (File f : files) if (f.getName().endsWith(".csv")) sb.append("\n[CSV 자료/").append(f.getName()).append("]\n").append(Files.readString(f.toPath(), StandardCharsets.UTF_8)).append('\n');
        int n = 0;
        StringBuilder key = new StringBuilder("\n[정답 키(출제자만 아는 정보)]\n");
        for (JsonNode q : questions) {
            if (!"TASK".equals(q.path("stage").asText())) continue;
            n++;
            sb.append("\n[문항 ").append(n).append(' ').append(q.path("type").asText()).append("] ").append(q.path("content").asText()).append('\n');
            int c = 0, right = 0;
            for (JsonNode ch : q.path("choices")) {
                sb.append("  보기").append(++c).append(' ').append(ch.path("content").asText()).append('\n');
                if (ch.path("correct").asBoolean()) right = c;
            }
            key.append("Q").append(n).append(": ").append("SINGLE".equals(q.path("type").asText()) ? "보기" + right : q.path("accepted").toString()).append('\n');
        }
        return sb.append(key).toString();
    }

    @Test
    void run() throws Exception {
        StageCallService calls = new StageCallService(ai, props, false);
        TaskReviewService review = new TaskReviewService(calls);
        StringBuilder out = new StringBuilder();
        for (Object[] c : CASES) {
            String no = (String) c[0];
            boolean expect = (Boolean) c[1];
            Path dir;
            try (var s = Files.list(RUN)) {
                dir = s.filter(p -> p.getFileName().toString().startsWith(no + "-")).findFirst().orElseThrow();
            }
            String in = input(dir);
            for (Stage st : List.of(Stage.REVIEW, Stage.REVIEW2)) {
                try {
                    var f = review.run(TaskReviewService.Check.LEAK, st, in);
                    boolean flagged = !f.violations().isEmpty();
                    out.append(no).append(" | ").append(st).append(" | 기대 ").append(expect ? "잡음" : "통과").append(" | 결과 ").append(flagged ? "잡음" : "통과")
                            .append(flagged == expect ? " | OK" : " | 어긋남");
                    for (var v : f.violations()) out.append("\n    - [").append(v.location()).append("] ").append(v.quote());
                    out.append('\n');
                    Files.writeString(Path.of("build", "leak-full.txt"), out.toString(), StandardCharsets.UTF_8); // 호출마다 남긴다(오래 걸림)
                } catch (RuntimeException e) {
                    out.append(no).append(" | ").append(st).append(" | 호출 실패 ").append(e.getMessage()).append('\n');
                }
            }
        }
        out.append("calls ").append(calls.usage().size()).append(", cost $").append(String.format("%.3f", StageCallService.cost(props, calls.usage()))).append('\n');
        Files.writeString(Path.of("build", "leak-full.txt"), out.toString(), StandardCharsets.UTF_8);
    }
}
