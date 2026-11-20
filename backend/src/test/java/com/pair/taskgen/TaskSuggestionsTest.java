package com.pair.taskgen;

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

import com.pair.ai.AiUnavailableException;
import com.pair.task.TaskRecord;
import com.pair.task.TaskRecordRepository;
import com.pair.web.auth.HeaderCurrentUserResolver;

/** 주제·함정 추천 API: 가짜 AI 로 개수·겹침 재요청·프롬프트 내용·권한·분당 한도를 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskSuggestionsTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    static class FakeAi implements TaskGenAi {
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
    @Autowired TaskRecordRepository records;

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

    private static TaskSuggestions.Suggestion s(String topic, String type) {
        return new TaskSuggestions.Suggestion("백엔드", "중급", topic, topic + " 함정", type, "AI 가 놓치기 쉽다");
    }

    private static TaskSuggestions.Result three() {
        return new TaskSuggestions.Result(List.of(s("쿠폰 할인", "경계값"), s("재고 차감", "동시성"), s("정산 합계", "반올림")));
    }

    @Test
    @DisplayName("후보 3개를 돌려주고 추천 모델(luna)로 AI 를 1번만 부른다")
    void threeSuggestions() throws Exception {
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0].topic").value("쿠폰 할인"))
                .andExpect(jsonPath("$.suggestions[0].trapType").value("경계값"))
                .andExpect(jsonPath("$.suggestions[0].why").exists());
        assertThat(ai.users).hasSize(1);
        assertThat(ai.models).containsExactly("gpt-6-luna");
    }

    @Test
    @DisplayName("이미 있는 과제 제목·소개와 exclude 가 프롬프트에 들어가고, 직무·난이도를 주면 그대로 지킨다")
    void promptHasExisting() throws Exception {
        TaskRecord t = new TaskRecord(9001L, "sug-existing", "겹치면 안 되는 제목", "DEV", 60, TaskRecord.Usage.SELF,
                TaskRecord.Status.HIDDEN, null, 1L, Instant.parse("2026-11-20T00:00:00Z"));
        t.setCard("겹치면 안 되는 소개", "기능 구현", "중급");
        records.save(t);
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"dev\",\"jobRole\":\"데이터 엔지니어\",\"difficulty\":\"고급\",\"exclude\":[\"이전에 받은 주제\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[1].jobRole").value("데이터 엔지니어"))
                .andExpect(jsonPath("$.suggestions[1].difficulty").value("고급"));
        String user = ai.users.get(0);
        assertThat(user).contains("겹치면 안 되는 제목", "겹치면 안 되는 소개", "이전에 받은 주제", "데이터 엔지니어", "고급");
    }

    @Test
    @DisplayName("trapType 이 겹치면 1번만 다시 요청하고, 다시 받은 3개를 돌려준다")
    void retryOnDuplicateType() throws Exception {
        ai.script.add(new TaskSuggestions.Result(List.of(s("a", "경계값"), s("b", "경계값"), s("c", "동시성"))));
        ai.script.add(three());
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(3));
        assertThat(ai.users).hasSize(2);
        assertThat(ai.users.get(1)).contains("조건을 지켜");
    }

    @Test
    @DisplayName("다시 해도 모자라면 쓸 만한 것만 돌려준다 (AI 는 2번까지만)")
    void usableOnly() throws Exception {
        TaskSuggestions.Suggestion blank = new TaskSuggestions.Suggestion("백엔드", "중급", " ", "x", "동시성", "y");
        ai.script.add(new TaskSuggestions.Result(List.of(s("a", "경계값"), blank)));
        ai.script.add(new TaskSuggestions.Result(List.of(s("a2", "경계값"))));
        mvc.perform(call("ADMIN", "{\"track\":\"BIZ\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(1));
        assertThat(ai.users).hasSize(2);
    }

    @Test
    @DisplayName("쓸 만한 것이 하나도 없거나 AI 호출이 실패하면 503")
    void unavailable() throws Exception {
        ai.script.add(new TaskSuggestions.Result(List.of()));
        ai.script.add(new TaskSuggestions.Result(List.of()));
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
    @DisplayName("입력 오류는 400 (모르는 track, 난이도, exclude 21개)")
    void badInput() throws Exception {
        mvc.perform(call("ADMIN", "{\"track\":\"XYZ\"}")).andExpect(status().isBadRequest());
        mvc.perform(call("ADMIN", "{}")).andExpect(status().isBadRequest());
        mvc.perform(call("ADMIN", "{\"track\":\"DEV\",\"difficulty\":\"최상급\"}")).andExpect(status().isBadRequest());
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
