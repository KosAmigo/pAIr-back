package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.TaskGenRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

import com.pair.infra.ai.AiUnavailableException;
import com.pair.infra.sandbox.CodeRunner;
import com.pair.domain.task.service.TaskDef;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.repository.TaskDefRepository;
import com.pair.global.security.HeaderCurrentUserResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 가짜 AI + 가짜 CodeRunner 로 생성 흐름(재생성, 저장, 실패 처리)과 API(권한, 동시 상한)를 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskGenFlowTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("pair.taskgen.max-concurrent", () -> "2");
    }

    /** 미리 정한 순서대로 Draft 나 오류를 돌려주는 가짜 AI. gate 가 있으면 열릴 때까지 기다린다 */
    static class ScriptedAi implements TaskGenAi {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> users = new ArrayList<>();
        volatile CountDownLatch gate;

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            users.add(user);
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
        TaskGenFixtures.FakeRunner fakeRunner() {
            return new TaskGenFixtures.FakeRunner();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    @Autowired ScriptedAi ai;
    @Autowired CodeRunner runner;
    @Autowired DevTaskGenerator generator;
    @Autowired TaskDefRepository tasks;
    @Autowired TaskRepository records;
    @Autowired GeneratedTaskWriter writer;
    @Autowired ObjectMapper json;

    private final List<String> steps = new ArrayList<>();

    @BeforeEach
    void reset() {
        ai.script.clear();
        ai.users.clear();
        ai.gate = null;
        steps.clear();
        ((TaskGenFixtures.FakeRunner) runner).bugCaught = true;
    }

    private static DevDraft good(String code) {
        DevDraft d = TaskGenFixtures.good();
        return new DevDraft(d.title(), d.summary(), code, d.category(), d.description(), d.requirements(), d.answer(),
                d.files(), d.hiddenTests(), d.solution());
    }

    private static DevDraft bad(String code) {
        DevDraft d = TaskGenFixtures.badSolution();
        return new DevDraft(d.title(), d.summary(), code, d.category(), d.description(), d.requirements(), d.answer(),
                d.files(), d.hiddenTests(), d.solution());
    }

    private TaskGenerator.Outcome generate() {
        return generator.generate(TaskGenFixtures.form(), 1L, steps::add);
    }

    @Test
    @DisplayName("처음에 통과하면 AI 를 1번만 부르고 검수 전(HIDDEN)으로 저장한다")
    void passFirstTime() throws Exception {
        ai.script.add(good("flow-pass"));
        var o = generate();

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(o.taskCode()).isEqualTo("flow-pass");
        assertThat(ai.users).hasSize(1);
        assertThat(steps).containsExactly("생성 중", "확인 중", "저장 중");

        Task t = records.findByCode("flow-pass").orElseThrow();
        assertThat(t.getStatus()).isEqualTo(Task.Status.HIDDEN);
        assertThat(t.getPublishedAt()).isNull();
        assertThat(t.getCreatedBy()).isEqualTo(1L);
        assertThat(t.getTrack()).isEqualTo("DEV");
        assertThat(t.getUsage()).isEqualTo(Task.Usage.SELF);
        assertThat(t.getTimeLimitMin()).isEqualTo(60);
        assertThat(t.getDifficulty()).isEqualTo("중급");
        assertThat(t.getCategory()).isEqualTo("장애 분석");
        assertThat(t.getSummary()).isEqualTo(good("x").summary());

        var report = json.readTree(writer.report("flow-pass"));
        assertThat(report.get("attempts").asInt()).isEqualTo(1);
        assertThat(report.get("passed").asBoolean()).isTrue();
        assertThat(report.get("inputTokens").asLong()).isEqualTo(1000);
        assertThat(report.get("outputTokens").asLong()).isEqualTo(2000);
        assertThat(report.get("checks")).hasSize(6);
        assertThat(report.get("form").get("topic").asText()).isEqualTo("쿠폰 할인 계산");
    }

    @Test
    @DisplayName("20분 과제: 제한 시간 20, d4Minutes 는 비례해서 줄고, AI 가 정한 난이도를 저장한다")
    void twentyMinutesAndAiDifficulty() {
        DevDraft g = good("flow-20min");
        ai.script.add(new DevDraft(g.title(), g.summary(), g.code(), g.category(), g.description(), g.requirements(), g.answer(),
                g.files(), g.hiddenTests(), g.solution(), "고급"));
        var form = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 중복 적용", "SELF", 20);
        generator.generate(form, 1L, steps::add);

        Task t = records.findByCode("flow-20min").orElseThrow();
        assertThat(t.getTimeLimitMin()).isEqualTo(20);
        assertThat(t.getDifficulty()).isEqualTo("고급");
        assertThat(tasks.get("flow-20min").d4Minutes()).containsExactly(6, 10, 13, 16);
        assertThat(ai.users.get(0)).contains("제한 시간: 20분").doesNotContain("직군", "난이도");
    }

    @Test
    @DisplayName("저장한 과제를 TaskDefRepository 로 다시 읽으면 Draft 와 같다 (숨은 테스트는 test/ 를 뗀 경로)")
    void roundTrip() {
        DevDraft d = good("flow-roundtrip");
        ai.script.add(d);
        generate();

        TaskDef def = tasks.get("flow-roundtrip");
        assertThat(def.type()).isEqualTo("dev");
        assertThat(def.title()).isEqualTo(d.title());
        assertThat(def.minutes()).isEqualTo(60);
        assertThat(def.description()).containsExactlyElementsOf(d.description());
        assertThat(def.requirements()).containsExactlyElementsOf(d.requirements());
        assertThat(def.followUps()).containsExactlyElementsOf(GeneratedTaskWriter.DEV_FOLLOW_UPS);
        assertThat(def.openFile()).isEqualTo("src/shop/Cart.java");
        assertThat(def.d4Minutes()).containsExactly(20, 30, 40, 50);

        assertThat(tasks.visibleFiles("flow-roundtrip")).containsOnlyKeys("src/shop/Cart.java", "test/shop/CartTest.java")
                .containsEntry("src/shop/Cart.java", d.files().get(0).content());
        assertThat(tasks.hiddenFiles("flow-roundtrip")).containsOnlyKeys("shop/HiddenCartTest.java", "answer.md",
                "_gen/report.json", "_gen/solution/src/shop/Cart.java.txt")
                .containsEntry("shop/HiddenCartTest.java", d.hiddenTests().get(0).content())
                .containsEntry("answer.md", d.answer())
                .containsEntry("_gen/solution/src/shop/Cart.java.txt", d.solution().get(0).content());
    }

    @Test
    @DisplayName("같은 code 로 또 만들면 -2, -3 이 붙는다")
    void duplicateCode() {
        ai.script.add(good("flow-dup"));
        ai.script.add(good("Flow Dup"));
        ai.script.add(good("flow-dup"));
        assertThat(generate().taskCode()).isEqualTo("flow-dup");
        assertThat(generate().taskCode()).isEqualTo("flow-dup-2");
        assertThat(generate().taskCode()).isEqualTo("flow-dup-3");
    }

    @Test
    @DisplayName("처음 검사에 실패하면 실패한 검사 이름과 내용을 붙여 1번만 다시 만들고 저장한다")
    void retryOnce() throws Exception {
        ai.script.add(bad("flow-retry-1"));
        ai.script.add(good("flow-retry-2"));
        var o = generate();

        assertThat(o.passed()).isTrue();
        assertThat(o.attempts()).isEqualTo(2);
        assertThat(o.taskCode()).isEqualTo("flow-retry-2");
        assertThat(ai.users).hasSize(2);
        assertThat(ai.users.get(1)).contains(DevChecks.SOLVED_PASSES).contains("테스트");
        assertThat(ai.users.get(0)).doesNotContain(DevChecks.SOLVED_PASSES);
        assertThat(steps).containsExactly("생성 중", "확인 중", "다시 생성 중", "확인 중", "저장 중");
        assertThat(records.findByCode("flow-retry-1")).isEmpty();
        var report = json.readTree(writer.report("flow-retry-2"));
        assertThat(report.get("attempts").asInt()).isEqualTo(2);
        assertThat(report.get("inputTokens").asLong()).isEqualTo(2000);
    }

    @Test
    @DisplayName("두 번 다 실패해도 실패 표시와 함께 저장한다 (AI 는 2번까지만 부른다)")
    void failTwiceStillSaved() throws Exception {
        ai.script.add(bad("flow-fail-1"));
        ai.script.add(bad("flow-fail-2"));
        ai.script.add(good("flow-never"));
        var o = generate();

        assertThat(o.passed()).isFalse();
        assertThat(o.attempts()).isEqualTo(2);
        assertThat(ai.users).hasSize(2);
        assertThat(o.checks()).filteredOn(c -> !c.ok()).extracting(GenCheckResult::name).containsExactly(DevChecks.SOLVED_PASSES);
        assertThat(records.findByCode("flow-fail-2").orElseThrow().getStatus()).isEqualTo(Task.Status.HIDDEN);
        var report = json.readTree(writer.report("flow-fail-2"));
        assertThat(report.get("passed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("다시 만들기 호출이 AI 오류로 실패하면 첫 결과를 실패 표시와 함께 저장한다")
    void retryAiError() throws Exception {
        ai.script.add(bad("flow-retry-err"));
        ai.script.add(new AiUnavailableException("OpenAI 요청 한도에 걸렸습니다 (429)."));
        var o = generate();

        assertThat(o.passed()).isFalse();
        assertThat(o.attempts()).isEqualTo(1);
        assertThat(json.readTree(writer.report("flow-retry-err")).get("retryError").asText()).contains("429");
    }

    @Test
    @DisplayName("첫 AI 호출이 실패하면 저장하지 않고 예외를 던진다")
    void aiErrorNothingSaved() {
        long before = records.count();
        ai.script.add(new AiUnavailableException("OpenAI API 키가 올바르지 않습니다 (401)."));
        assertThatThrownBy(this::generate).isInstanceOf(AiUnavailableException.class);
        assertThat(records.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("gen-report 는 AI 로 만들지 않은 과제나 없는 과제면 404")
    void reportNotFound() {
        assertThatThrownBy(() -> writer.report("no-such-task")).isInstanceOf(NoSuchElementException.class);
    }

    // ── API ─────────────────────────────────────────────

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role)
                .header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    private static final String FORM = """
            {"track":"DEV","topic":"쿠폰 할인 계산","trap":"쿠폰 중복 적용"}
            """;

    @Test
    @DisplayName("ADMIN 이 아니면 세 API 모두 403")
    void notAdminForbidden() throws Exception {
        for (String role : List.of("USER", "MANAGER")) {
            mvc.perform(as(post("/api/admin/task-gen"), role).contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isForbidden());
            mvc.perform(as(get("/api/admin/task-gen/x"), role)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/admin/tasks/x/gen-report"), role)).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("폼 검사: 모르는 track, 빈 값·범위 밖은 400")
    void formValidation() throws Exception {
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(FORM.replace("DEV", "XYZ"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DEV 또는 BIZ")));
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(FORM.replace("쿠폰 할인 계산", " "))).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(FORM.replace("}", ",\"minutes\":30}"))).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(FORM.replace("}", ",\"minutes\":90}"))).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content(FORM.replace("}", ",\"usage\":\"OTHER\"}"))).andExpect(status().isBadRequest());
    }

    private String waitDone(String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(as(get("/api/admin/task-gen/" + jobId), "ADMIN")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (!json.readTree(body).get("status").asText().equals("RUNNING")) return body;
            Thread.sleep(100);
        }
        throw new AssertionError("작업이 끝나지 않았습니다");
    }

    private String startJob() throws Exception {
        String body = mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON).content(FORM))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("jobId").asText();
    }

    @Test
    @DisplayName("생성 작업: 202 jobId → 상태 조회로 DONE 과 taskCode → gen-report 조회")
    void jobEndToEnd() throws Exception {
        ai.script.add(good("flow-api"));
        var done = json.readTree(waitDone(startJob()));
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("taskCode").asText()).isEqualTo("flow-api");
        assertThat(done.get("passed").asBoolean()).isTrue();
        assertThat(done.get("checks")).hasSize(6);

        mvc.perform(as(get("/api/admin/tasks/flow-api/gen-report"), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(1)).andExpect(jsonPath("$.checks.length()").value(6));
        mvc.perform(as(get("/api/admin/tasks/order-deadlock/gen-report"), "ADMIN")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/admin/task-gen/no-such-job"), "ADMIN")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AI 오류면 작업은 FAILED, 과제는 저장되지 않는다")
    void jobFailed() throws Exception {
        long before = records.count();
        ai.script.add(new AiUnavailableException("OpenAI 요청 한도에 걸렸습니다 (429)."));
        var done = json.readTree(waitDone(startJob()));
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("error").asText()).contains("429");
        assertThat(records.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("동시에 도는 작업이 상한(2)을 넘으면 429, 끝나면 다시 받는다")
    void concurrencyLimit() throws Exception {
        ai.gate = new CountDownLatch(1);
        ai.script.add(good("flow-limit-1"));
        ai.script.add(good("flow-limit-2"));
        String a = startJob();
        String b = startJob();
        mvc.perform(as(post("/api/admin/task-gen"), "ADMIN").contentType(MediaType.APPLICATION_JSON).content(FORM))
                .andExpect(status().isTooManyRequests());

        ai.gate.countDown();
        waitDone(a);
        waitDone(b);
        ai.script.add(good("flow-limit-3"));
        waitDone(startJob());
    }
}
