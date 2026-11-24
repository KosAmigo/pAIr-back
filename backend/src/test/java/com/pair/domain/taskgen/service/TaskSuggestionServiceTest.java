package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.security.HeaderCurrentUserResolver;

/** 주제·함정 추천 API: 가짜 AI 로 개수·겹침 재요청·프롬프트 내용·권한·분당 한도를 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
@Tag("slow")
class TaskSuggestionServiceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    static class FakeAi implements TaskGenAiService {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> users = new ArrayList<>();
        final List<String> models = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            models.add(model);
            users.add(user);
            Object next = script.poll();
            if (next instanceof RuntimeException e) throw e;
            if (next == null) throw new AiUnavailableException("준비된 답이 없습니다.");
            return new Output<>((T) next, 100, 200);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeAi fakeAi() {
            return new FakeAi();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    @Autowired FakeAi ai;
    @Autowired TaskRepository records;

    private static int nextAdmin = 100; // 분당 한도가 테스트끼리 섞이지 않게 테스트마다 다른 관리자 번호
    private int admin;

    @BeforeEach
    void reset() {
        ai.script.clear();
        ai.users.clear();
        ai.models.clear();
        admin = nextAdmin++;
    }

    private MockHttpServletRequestBuilder call(String role, String body) {
        return post("/api/admin/task-gen/suggestions").header(HeaderCurrentUserResolver.USER_ID, admin)
                .header(HeaderCurrentUserResolver.ROLE, role).header(HeaderCurrentUserResolver.COMPANY_ID, 601).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static TaskSuggestionService.Suggestion s(String situation, String type) {
        return new TaskSuggestionService.Suggestion(situation, situation + " 함정", type, List.of("control", "delivery"), "AI 가 놓치기 쉽다");
    }

    private static TaskSuggestionService.Result three() {
        return new TaskSuggestionService.Result(null, List.of(s("쿠폰 할인", "경계값"), s("재고 차감", "동시성"), s("정산 합계", "반올림")));
    }

    private static TaskSuggestionService.Result three(TaskSuggestionService.TopicCheck check) {
        return new TaskSuggestionService.Result(check, three().suggestions());
    }

    @Test
    @DisplayName("주제가 없으면 후보 3개와 topicCheck=null 을 돌려주고 추천 모델(luna)로 AI 를 1번만 부른다")
    void threeSuggestions() throws Exception {
        // AI 가 topicCheck 를 채워 보내도 주제가 없으면 null
        ai.script.add(three(new TaskSuggestionService.TopicCheck(true, "아무거나", null)));
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.topicCheck").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.suggestions.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0].situation").value("쿠폰 할인"))
                .andExpect(jsonPath("$.suggestions[0].trap").value("쿠폰 할인 함정"))
                .andExpect(jsonPath("$.suggestions[0].trapType").value("경계값"))
                .andExpect(jsonPath("$.suggestions[0].competencies[0]").value("control"))
                .andExpect(jsonPath("$.suggestions[0].why").exists());
        assertThat(ai.users).hasSize(1);
        assertThat(ai.models).containsExactly("gpt-6-luna");
        assertThat(ai.users.get(0)).contains("주제: 정해지지 않음");
    }

    @Test
    @DisplayName("주제가 괜찮으면 topicCheck.ok=true(message 없음), 프롬프트에 주제가 들어간다")
    void topicOk() throws Exception {
        ai.script.add(three(new TaskSuggestionService.TopicCheck(true, "결제 처리 흐름", "")));
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\",\"topic\":\"결제 처리\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.topicCheck.ok").value(true))
                .andExpect(jsonPath("$.topicCheck.understood").value("결제 처리 흐름"))
                .andExpect(jsonPath("$.topicCheck.message").doesNotExist())
                .andExpect(jsonPath("$.suggestions.length()").value(3));
        assertThat(ai.users.get(0)).contains("주제: 결제 처리");
    }

    @Test
    @DisplayName("주제가 이상하면 ok=false + 이해한 뜻 + 이유를 주면서도 추천 3개를 한 번에 돌려준다")
    void topicNotOk() throws Exception {
        ai.script.add(three(new TaskSuggestionService.TopicCheck(false, "회사 전체 시스템", "주제가 너무 넓거나 과제로 만들기 어려워 보여요")));
        mvc.perform(call("ADMIN", "{\"track\":\"BIZ\",\"topic\":\"회사\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.topicCheck.ok").value(false))
                .andExpect(jsonPath("$.topicCheck.understood").value("회사 전체 시스템"))
                .andExpect(jsonPath("$.topicCheck.message").value("주제가 너무 넓거나 과제로 만들기 어려워 보여요"))
                .andExpect(jsonPath("$.suggestions.length()").value(3));
        assertThat(ai.users).hasSize(1);
    }

    @Test
    @DisplayName("100자가 넘는 주제는 400")
    void topicTooLong() throws Exception {
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\",\"topic\":\"" + "가".repeat(101) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("역량 key 는 6개 중에서만 남기고(중복 제거, 최대 2개), 하나도 안 맞으면 그 후보는 버린다")
    void competencyValidation() throws Exception {
        var fixed = new TaskSuggestionService.Suggestion("a", "a 함정", "경계값", List.of("Control", "control", "nope", "delivery", "usage"), "w");
        var none = new TaskSuggestionService.Suggestion("b", "b 함정", "동시성", List.of("nope", "x"), "w");
        ai.script.add(new TaskSuggestionService.Result(null, List.of(fixed, none, s("c", "반올림"), s("d", "캐시"))));
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0].competencies.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].competencies[0]").value("control"))
                .andExpect(jsonPath("$.suggestions[0].competencies[1]").value("delivery"))
                .andExpect(jsonPath("$.suggestions[1].situation").value("c"));
        assertThat(ai.users).hasSize(1);
    }

    @Test
    @DisplayName("이미 있는 과제 제목·소개와 exclude 가 프롬프트에 들어가고, jobRole·difficulty 를 보내도 무시한다")
    void promptHasExisting() throws Exception {
        Task t = new Task(9001L, "sug-existing", "겹치면 안 되는 제목", "DEV", 60, Task.Usage.SELF,
                Task.Status.HIDDEN, null, 1L, Instant.parse("2026-11-20T00:00:00Z"));
        t.setCard("겹치면 안 되는 소개", "기능 구현", "보통");
        records.save(t);
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"dev\",\"jobRole\":\"데이터 엔지니어\",\"difficulty\":\"최상급\",\"exclude\":[\"이전에 받은 상황\"]}"))
                .andExpect(status().isOk());
        String user = ai.users.get(0);
        assertThat(user).contains("겹치면 안 되는 제목", "겹치면 안 되는 소개", "이전에 받은 상황").doesNotContain("데이터 엔지니어", "최상급", "난이도", "직무");
    }

    @Test
    @DisplayName("trapType 이 겹치면 1번만 다시 요청하고, 다시 받은 3개를 돌려준다")
    void retryOnDuplicateType() throws Exception {
        ai.script.add(new TaskSuggestionService.Result(null, List.of(s("a", "경계값"), s("b", "경계값"), s("c", "동시성"))));
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(3));
        assertThat(ai.users).hasSize(2);
        assertThat(ai.users.get(1)).contains("조건을 지켜");
    }

    @Test
    @DisplayName("다시 해도 모자라면 쓸 만한 것만 돌려준다 (AI 는 2번까지만)")
    void usableOnly() throws Exception {
        TaskSuggestionService.Suggestion blank = new TaskSuggestionService.Suggestion(" ", "x", "동시성", List.of("usage"), "y");
        ai.script.add(new TaskSuggestionService.Result(null, List.of(s("a", "경계값"), blank)));
        ai.script.add(new TaskSuggestionService.Result(null, List.of(s("a2", "경계값"))));
        mvc.perform(call("ADMIN", "{\"track\":\"BIZ\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(1));
        assertThat(ai.users).hasSize(2);
    }

    @Test
    @DisplayName("쓸 만한 것이 하나도 없거나 AI 호출이 실패하면 503")
    void unavailable() throws Exception {
        ai.script.add(new TaskSuggestionService.Result(null, List.of()));
        ai.script.add(new TaskSuggestionService.Result(null, List.of()));
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isServiceUnavailable());

        ai.script.clear();
        ai.script.add(new AiUnavailableException("키가 없습니다"));
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("키가 없습니다"));
    }

    @Test
    @DisplayName("ADMIN 이 아니면 403")
    void forbidden() throws Exception {
        for (String role : List.of("USER", "MANAGER")) {
            mvc.perform(call(role, "{\"track\":\"DEV\"}")).andExpect(status().isForbidden());
        }
        assertThat(ai.users).isEmpty();
    }

    @Test
    @DisplayName("입력 오류는 400 (모르는 track, exclude 21개)")
    void badInput() throws Exception {
        mvc.perform(call("ADMIN", "{\"track\":\"XYZ\"}")).andExpect(status().isBadRequest());
        mvc.perform(call("ADMIN", "{}")).andExpect(status().isBadRequest());
        String many = "\"a\",".repeat(20) + "\"b\"";
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\",\"exclude\":[" + many + "]}")).andExpect(status().isBadRequest());
        assertThat(ai.users).isEmpty();
    }

    @Test
    @DisplayName("관리자 1명당 1분에 10번이 넘으면 429")
    void rateLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            ai.script.add(three());
            mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk());
        }
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isTooManyRequests());
    }
}
