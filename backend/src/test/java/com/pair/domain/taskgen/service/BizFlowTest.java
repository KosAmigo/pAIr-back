package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.infra.storage.TaskFileStorage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.infra.ai.AiUnavailableException;
import com.pair.domain.task.repository.DocumentPageRepository;
import com.pair.domain.task.entity.QuestionChoice;
import com.pair.domain.task.repository.QuestionChoiceRepository;
import com.pair.domain.task.repository.QuestionSourceRepository;
import com.pair.domain.task.entity.ShortAnswerKey;
import com.pair.domain.task.repository.ShortAnswerKeyRepository;
import com.pair.domain.task.dto.TaskDefDto;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.repository.TaskDefRepository;
import com.pair.global.security.HeaderCurrentUserResolver;

/** 비개발 생성 흐름: 가짜 AI + 가짜 풀이 모델, 실제 PDF 와 로컬 파일 저장소, H2 DB */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
@Tag("slow")
class BizFlowTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("pair.taskgen.file-storage", () -> "local");
        r.add("pair.taskgen.worker.enabled", () -> "true"); // 이 테스트는 대기열 워커를 켜고 tick() 으로 바로 돌린다
        r.add("pair.taskgen.pipeline", () -> "oneshot"); // 한 번에 과제 전체를 돌려주는 가짜 AI 용
    }

    static class ScriptedAi implements TaskGenAiService {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> users = new ArrayList<>();
        /** "AI 답을 그대로 내면 함정에 걸림" 검사 호출(SoloBizAnswerDto)은 따로 모은다. 준비된 답이 없으면 호출 실패(warn) */
        final Deque<Object> soloScript = new ArrayDeque<>();
        final List<String> soloUsers = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            if (type == com.pair.domain.taskgen.dto.SoloBizAnswerDto.class) {
                soloUsers.add(user);
                Object s = soloScript.poll();
                if (s instanceof RuntimeException e) throw e;
                if (s == null) throw new AiUnavailableException("준비된 풀이가 없습니다.");
                return new Output<>((T) s, 600, 40);
            }
            users.add(user);
            Object next = script.poll();
            if (next instanceof RuntimeException e) throw e;
            if (next == null) throw new AiUnavailableException("준비된 답이 없습니다.");
            return new Output<>((T) next, 1000, 2000);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ScriptedAi scriptedAi() {
            return new ScriptedAi();
        }

        @Bean
        @Primary
        BizFixtures.FakeSolver fakeSolver() {
            return new BizFixtures.FakeSolver();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired com.pair.domain.taskgen.repository.TaskGenJobRepository jobRepo;
    @Autowired TaskGenJobService jobSvc;
    @MockitoBean JavaMailSender mail;
    @Autowired ScriptedAi ai;
    @Autowired BizFixtures.FakeSolver solver;
    @Autowired BizTaskGeneratorService generator;
    @Autowired GeneratedTaskRepository writer;
    @Autowired TaskFileStorage storage;
    @Autowired TaskDefRepository tasks;
    @Autowired TaskRepository records;
    @Autowired TaskFileRepository files;
    @Autowired TaskQuestionRepository questions;
    @Autowired QuestionChoiceRepository choices;
    @Autowired ShortAnswerKeyRepository answerKeys;
    @Autowired QuestionSourceRepository sources;
    @Autowired DocumentPageRepository pages;
    @Autowired ObjectMapper json;

    private final List<String> steps = new ArrayList<>();

    @BeforeEach
    void reset() {
        ai.script.clear();
        ai.users.clear();
        ai.soloScript.clear();
        ai.soloUsers.clear();
        steps.clear();
        jobRepo.deleteAll(); // 앞선 테스트가 대기열에 남긴 작업이 이 테스트의 AI 호출을 가로채지 않게
        solver.error = null;
        solver.reply = "{\"answers\": [\"1\", \"2\", \"21080\", \"2000\"]}";
    }

    private TaskGeneratorService.Outcome generate() {
        return generator.generate(BizFixtures.form(), 1L, steps::add);
    }

    private static BizDraftDto wrongAnswers(String code) {
        // 정답 계산이 인정 답안과 달라 '단답형 검산' 이 실패하는 Draft
        BizDraftDto d = BizFixtures.good(code);
        var bad = BizFixtures.shortQ("q", "11111", "v(\"자료/매출.csv\", \"월=2026-09;요금제=베이직\", \"가입자 수\")", BizFixtures.CSV + "|", BizFixtures.DOC1 + "|2");
        return BizFixtures.edit(d, b -> b.questions(BizFixtures.replaceQuestion(d, 2, bad)));
    }

    private long pdfFilesOnDisk() throws IOException {
        Path root = dataDir.resolve("task-files");
        if (!Files.exists(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".pdf")).count();
        }
    }

    // ── 저장 내용 ─────────────────────────────

    @Test
    @DisplayName("통과하면 AI 1번 호출로 검수 전(HIDDEN)에 저장하고, 문항·보기·정답·근거·쪽 글·PDF 가 모두 들어간다")
    void passAndSave() throws Exception {
        BizDraftDto d = BizFixtures.good("biz-pass");
        ai.script.add(d);
        var o = generate();

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(o.checks()).hasSize(16); // 15 + 참고: 필요한 자료만 붙인 단독 풀이
        assertThat(steps).containsExactly("생성 중", "확인 중", "저장 중");
        assertThat(solver.lastModel).isEqualTo("claude-sonnet-5-5");

        Task t = records.findByCode("biz-pass").orElseThrow();
        long id = t.getId();
        assertThat(t.getStatus()).isEqualTo(Task.Status.HIDDEN);
        assertThat(t.getPublishedAt()).isNull();
        assertThat(t.getTrack()).isEqualTo("BIZ");
        assertThat(t.getCategory()).isEqualTo("기획안 작성");
        assertThat(t.getSettings()).contains("\"openFile\":\"기획안.md\"").contains("\"editableFile\":\"기획안.md\"").contains("## 결론").contains("[20,30,40,50]");

        // TaskDefRepository 로 다시 읽기
        TaskDefDto def = tasks.get("biz-pass");
        assertThat(def.type()).isEqualTo("biz");
        assertThat(def.description()).containsExactlyElementsOf(d.description());
        assertThat(def.followUps()).containsExactlyElementsOf(d.followUps());
        assertThat(def.requiredHeadings()).containsExactly("## 결론", "## 근거", "## 리스크");
        assertThat(tasks.visibleFiles("biz-pass")).containsOnlyKeys("자료/매출.csv", "기획안.md")
                .containsEntry("자료/매출.csv", d.csvFiles().get(0).content());
        assertThat(tasks.visibleFiles("biz-pass").get("기획안.md")).contains("## 결론").contains("## 근거").contains("## 리스크");
        assertThat(tasks.hiddenFiles("biz-pass")).containsOnlyKeys("checklist.md", "_gen/report.json").containsEntry("checklist.md", d.checklist());

        // PDF: storage_key 만, 내용은 비움
        TaskFile pdf = files.findByTaskIdAndPath(id, BizFixtures.DOC1).orElseThrow();
        assertThat(pdf.getContent()).isNull();
        assertThat(pdf.getMimeType()).isEqualTo("application/pdf");
        assertThat(pdf.getVisibility()).isEqualTo(TaskFile.VISIBLE);
        assertThat(pdf.getStorageKey()).isEqualTo("task-files/" + id + "/1.pdf");
        byte[] bytes = storage.get(pdf.getStorageKey()).orElseThrow();
        assertThat(pdf.getSizeBytes()).isEqualTo(bytes.length);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        var docPages = pages.findByTaskFileIdOrderByPageNoAsc(pdf.getId());
        assertThat(docPages).extracting(p -> p.getText()).containsExactlyElementsOf(d.documents().get(0).pages());

        // 문항
        var qs = questions.findByTaskIdAndStageOrderBySeqAsc(id, "TASK");
        assertThat(qs).extracting(TaskQuestion::getQuestionType).containsExactly("SINGLE", "SINGLE", "SHORT", "SHORT");
        assertThat(qs).extracting(TaskQuestion::getPoints).containsExactly(5, 5, 3, 3);
        assertThat(qs).extracting(TaskQuestion::getContent).containsExactlyElementsOf(d.questions().stream().map(BizDraftDto.QuestionDraft::content).toList());
        var c1 = choices.findByQuestionIdOrderByIdAsc(qs.get(0).getId());
        assertThat(c1).extracting(QuestionChoice::getContent).containsExactly("34%", "38%", "41%", "50%");
        assertThat(c1).extracting(QuestionChoice::isCorrect).containsExactly(true, false, false, false);
        assertThat(answerKeys.findByQuestionIdOrderByIdAsc(qs.get(2).getId())).extracting(ShortAnswerKey::getAccepted).containsExactly("21080");
        assertThat(answerKeys.findByQuestionIdOrderByIdAsc(qs.get(0).getId())).isEmpty();

        // 근거: 문항마다 2개 이상, 쪽 번호
        var src1 = sources.findByQuestionId(qs.get(0).getId());
        assertThat(src1).hasSize(2);
        assertThat(src1).extracting(s -> s.getPages()).containsExactlyInAnyOrder("2", "1");
        var csvId = files.findByTaskIdAndPath(id, BizFixtures.CSV).orElseThrow().getId();
        var src3 = sources.findByQuestionId(qs.get(2).getId());
        assertThat(src3).extracting(s -> s.getTaskFileId()).contains(csvId);
        assertThat(src3).filteredOn(s -> s.getTaskFileId().equals(csvId)).extracting(s -> s.getPages()).containsOnlyNulls();

        // 후속 질문
        assertThat(questions.findByTaskIdAndStageOrderBySeqAsc(id, "FOLLOWUP")).extracting(TaskQuestion::getContent).containsExactlyElementsOf(d.followUps());

        // report.json: 함정, 검사 7개
        var report = json.readTree(writer.report("biz-pass"));
        assertThat(report.get("checks")).hasSize(16);
        assertThat(report.get("details").get("trap").get("evidence").asText()).isEqualTo(BizFixtures.EVIDENCE);
        assertThat(report.get("details").get("solverModel").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(report.get("form").get("track").asText()).isEqualTo("BIZ");
    }

    @Test
    @DisplayName("저장 중 DB 오류로 롤백되면 이미 올린 PDF 를 지우고 과제도 남지 않는다")
    void rollbackDeletesPdf() throws Exception {
        long before = pdfFilesOnDisk();
        long tasksBefore = records.count();
        // 인정 답안이 칼럼 길이(200)를 넘으면 단답형 정답 저장에서 DB 오류가 난다 (PDF 는 이미 올라간 뒤)
        BizDraftDto d = BizFixtures.good("biz-rollback");
        var q = BizFixtures.shortQ("긴 답", "21080", "1", BizFixtures.CSV + "|", BizFixtures.DOC1 + "|2");
        var tooLong = new BizDraftDto.QuestionDraft(q.type(), q.content(), q.choices(), List.of("21080", "9".repeat(300)), q.calc(), q.sources());
        BizDraftDto broken = BizFixtures.edit(d, b -> b.questions(BizFixtures.replaceQuestion(d, 2, tooLong)));
        var report = new TaskGeneratorService.GenReport("m", 1, 1, 1, 1, true, BizFixtures.form(), List.of(), null, Map.of());

        assertThatThrownBy(() -> writer.saveBiz(BizFixtures.form(), broken, report, 1L)).isInstanceOf(RuntimeException.class);

        assertThat(records.findByCode("biz-rollback")).isEmpty();
        assertThat(records.count()).isEqualTo(tasksBefore);
        assertThat(pdfFilesOnDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 code 로 또 만들면 -2 가 붙고 PDF 는 과제 번호별로 따로 저장된다")
    void duplicateCode() throws Exception {
        ai.script.add(BizFixtures.good("biz-dup"));
        ai.script.add(BizFixtures.good("biz-dup"));
        assertThat(generate().taskCode()).isEqualTo("biz-dup");
        assertThat(generate().taskCode()).isEqualTo("biz-dup-2");
        long a = records.findByCode("biz-dup").orElseThrow().getId();
        long b = records.findByCode("biz-dup-2").orElseThrow().getId();
        assertThat(storage.get("task-files/" + a + "/1.pdf")).isPresent();
        assertThat(storage.get("task-files/" + b + "/2.pdf")).isPresent();
    }

    // ── 생성 흐름 ─────────────────────────────

    @Test
    @DisplayName("처음 검사에 실패하면 실패한 검사 이름·내용을 붙여 1번만 다시 만들고, 고쳐지면 그 결과를 저장한다")
    void retryOnce() {
        ai.script.add(wrongAnswers("biz-retry-1"));
        ai.script.add(BizFixtures.good("biz-retry-2"));
        var o = generate();

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(2);
        assertThat(o.taskCode()).isEqualTo("biz-retry-2");
        assertThat(ai.users.get(1)).contains(BizCheckService.CALC).contains("11111");
        assertThat(steps).containsExactly("생성 중", "확인 중", "다시 생성 중", "확인 중", "저장 중");
        assertThat(records.findByCode("biz-retry-1")).isEmpty();
    }

    @Test
    @DisplayName("두 번 다 실패해도 실패 표시와 함께 검수 전으로 저장한다")
    void failTwiceStillSaved() throws Exception {
        ai.script.add(wrongAnswers("biz-fail-1"));
        ai.script.add(wrongAnswers("biz-fail-2"));
        var o = generate();

        assertThat(o.passed()).isFalse();
        assertThat(o.attempts()).isEqualTo(2);
        assertThat(ai.users).hasSize(2);
        assertThat(o.checks().stream().filter(c -> !c.ok()).map(GenCheckResultDto::name)).contains(BizCheckService.CALC);
        assertThat(records.findByCode("biz-fail-2").orElseThrow().getStatus()).isEqualTo(Task.Status.HIDDEN);
        assertThat(json.readTree(writer.report("biz-fail-2")).get("passed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("풀이 모델 호출이 실패하면 그 검사만 실패하고, 첫 AI 호출 실패는 저장 없이 예외")
    void errors() {
        solver.error = new AiUnavailableException("Claude 요청 한도 (429)");
        ai.script.add(BizFixtures.good("biz-solver-err"));
        var o = generate();
        assertThat(o.checks().stream().filter(c -> !c.ok()).map(GenCheckResultDto::name)).containsExactly(BizCheckService.SOLVE);

        long before = records.count();
        ai.script.clear();
        ai.script.add(new AiUnavailableException("OpenAI API 키가 올바르지 않습니다 (401)."));
        assertThatThrownBy(this::generate).isInstanceOf(AiUnavailableException.class);
        assertThat(records.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("LIVE 에서 AI 가 함정 문항을 모두 맞혀도 다시 만들지 않고 '잡음'으로 기록해 통과·저장한다 (참고 지표)")
    void soloSolvedOnlyRecordedForLive() throws Exception {
        ai.script.add(BizFixtures.good("biz-live-1"));
        ai.script.add(BizFixtures.good("biz-live-never"));
        ai.soloScript.add(BizFixtures.solo("1", "2", "21080", "2000")); // 필요한 자료만 붙인 풀이(먼저 돎)
        ai.soloScript.add(BizFixtures.solo("1", "2", "21080", "2000"));
        var live = new com.pair.domain.taskgen.dto.TaskGenRequest("BIZ", "저가 요금제 출시 여부", "설문 의향을 실제 전환율로 보정하지 않음", "LIVE", 60);
        var o = generator.generate(live, 1L, steps::add);

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(o.taskCode()).isEqualTo("biz-live-1");
        assertThat(ai.users).hasSize(1);
        assertThat(ai.soloUsers).hasSize(2); // 필요한 자료만 1번 + 고른 자료 1번
        assertThat(steps).containsExactly("생성 중", "확인 중", "저장 중");
        assertThat(o.checks()).filteredOn(c -> c.name().equals(BizSoloSolveService.NAME)).singleElement().satisfies(c -> {
            assertThat(c.ok()).isTrue();
            assertThat(c.level()).isEqualTo(GenCheckResultDto.WARN);
            assertThat(c.detail()).contains("숨은 결함을 잡았어요", "AI 답 21080");
        });
        var report = json.readTree(writer.report("biz-live-1"));
        assertThat(report.get("details").get("soloAnswers").get(2).asText()).isEqualTo("21080");
        assertThat(report.get("details").get("soloTokens").get("input").asLong()).isEqualTo(600);
        // E2·E3 보고용 정보
        assertThat(report.get("details").get("judgmentPoints").get(0).get("question").asText()).isEqualTo(BizFixtures.JUDGMENT.question());
        assertThat(report.get("details").get("hiddenFlaws").get(0).get("caughtBy").asText()).isEqualTo("Q3, 결론");
    }

    @Test
    @DisplayName("SELF 에서 AI 가 함정에 빠져도(지나침) 통과하고 기록만 남는다")
    void soloMissedRecorded() {
        ai.script.add(BizFixtures.good("biz-self-1"));
        ai.soloScript.add(BizFixtures.solo("1", "2", "21080", "2000")); // 필요한 자료만 붙인 풀이(먼저 돎)
        ai.soloScript.add(BizFixtures.solo("1", "2", "23560", "2000"));
        var o = generate();

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(o.checks()).filteredOn(c -> c.name().equals(BizSoloSolveService.NAME)).singleElement().satisfies(c -> {
            assertThat(c.ok()).isTrue();
            assertThat(c.detail()).contains("숨은 결함을 지나쳤어요", "Q3(AI 답 23560, 정답 21080)");
        });
    }

    @Test
    @DisplayName("E2: 판단 지점의 근거 문장이 자료에 없으면 fail 이라 1회 다시 만들고, 실패 내용이 프롬프트에 들어간다")
    void judgmentFailRegenerates() {
        var missing = new com.pair.domain.taskgen.dto.DevDraftDto.JudgmentPoint("정할 것", java.util.List.of("가", "나"), "자료 어디에도 없는 문장입니다 정말로");
        ai.script.add(BizFixtures.edit(BizFixtures.good("biz-e2-1"), b -> b.judgmentPoints(java.util.List.of(missing))));
        ai.script.add(BizFixtures.good("biz-e2-2"));
        ai.soloScript.add(BizFixtures.solo("1", "2", "21080", "2000")); // 필요한 자료만 붙인 풀이(먼저 돎)
        ai.soloScript.add(BizFixtures.solo("1", "2", "23560", "2000"));
        var o = generate();

        assertThat(o.attempts()).isEqualTo(2);
        assertThat(o.passed()).isTrue();
        assertThat(ai.users.get(1)).contains(BizCheckService.JUDGMENT, "근거 문장을 응시자 자료에서 찾지 못했습니다");
        assertThat(ai.soloUsers).hasSize(2); // 첫 Draft 는 fail 이라 돌리지 않고 최종 Draft 에만(필요한 자료만 1번 + 고른 자료 1번)
    }

    // ── API ─────────────────────────────────

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, "ADMIN")
                .header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    @Test
    @DisplayName("POST /api/admin/task-gen 에 track=BIZ 를 내면 비개발 생성 작업이 돌고 DONE 이 된다")
    void bizJobViaApi() throws Exception {
        ai.script.add(BizFixtures.good("biz-api"));
        String form = "{\"track\":\"BIZ\",\"topic\":\"저가 요금제\",\"trap\":\"의향을 전환율로 보정하지 않음\"}";
        String body = mvc.perform(admin(post("/api/admin/task-gen")).contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = json.readTree(body).get("jobId").asText();
        String done = null;
        for (int i = 0; i < 100; i++) {
            done = mvc.perform(admin(get("/api/admin/task-gen/" + jobId))).andReturn().getResponse().getContentAsString();
            String st = json.readTree(done).get("status").asText();
            if (!st.equals("RUNNING") && !st.equals("QUEUED")) break;
            jobSvc.tick(); // 테스트는 워커를 끄고 대기열을 직접 돌린다
            Thread.sleep(100);
        }
        var j = json.readTree(done);
        assertThat(j.get("status").asText()).as(done).isEqualTo("DONE");
        assertThat(j.get("taskCode").asText()).isEqualTo("biz-api");
        assertThat(j.get("checks")).hasSize(16);
        assertThat(records.findByCode("biz-api").orElseThrow().getTrack()).isEqualTo("BIZ");
        assertThat(pdfFilesOnDisk()).isPositive();
    }
}
