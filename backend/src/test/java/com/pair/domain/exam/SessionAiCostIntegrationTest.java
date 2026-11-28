package com.pair.domain.exam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.entity.ChatTurn;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.ChatTurnRepository;
import com.pair.domain.grader.repository.PromptVersionStateRepository;
import com.pair.domain.scoring.entity.JudgeCall;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.scoring.repository.JudgeCallRepository;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.scoring.service.ScoringCatalogService;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.security.HeaderCurrentUserResolver;

/**
 * 회차 AI 비용: 회차 응시자의 AI 채팅·채점 토큰을 모델별로 더해 단가(application.yml pair.pricing)를 곱한다. 관리자만 본다.
 * 단가: claude-haiku-5-5 입력 0.10 / 출력 0.50, gpt-6.1-terra 입력 1.0 / 출력 5.0 ($/100만 토큰)
 */
class SessionAiCostIntegrationTest extends ExamIntegrationTest {

    private static final long COMPANY = 621, MANAGER = 51;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.pair.domain.task.repository.TaskRepository taskRecords;
    @Autowired com.pair.domain.task.repository.TaskFileRepository taskFiles;
    @Autowired com.pair.domain.task.repository.TaskQuestionRepository taskQuestions;
    @Autowired AttemptRepository attempts;
    @Autowired ChatTurnRepository turns;
    @Autowired ScoringRepository scorings;
    @Autowired JudgeCallRepository judgeCalls;
    @Autowired ScoringCatalogService catalog;
    @Autowired PromptVersionStateRepository prompts;

    long session, emptySession;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, long user, String role, Long company) {
        b.header(HeaderCurrentUserResolver.USER_ID, user).header(HeaderCurrentUserResolver.ROLE, role);
        return company == null ? b : b.header(HeaderCurrentUserResolver.COMPANY_ID, company);
    }

    private long createSession() throws Exception {
        Instant start = Instant.now().plusSeconds(3600);
        String body = """
                {"title": "cost", "startsAt": "%s", "endsAt": "%s", "proctoring": false, "taskIds": ["order-deadlock-live"]}
                """.formatted(start, start.plusSeconds(3600));
        String res = mvc.perform(as(post("/api/console/sessions"), MANAGER, "MANAGER", COMPANY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("id").asLong();
    }

    @BeforeEach
    void setUp() throws Exception {
        com.pair.domain.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        session = createSession();
        emptySession = createSession();
        mvc.perform(as(post("/api/console/sessions/" + session + "/participants"), MANAGER, "MANAGER", COMPANY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participants\":[{\"name\":\"대상자\",\"email\":\"cost-" + session + "@test.dev\"}]}"))
                .andExpect(status().isOk());
        long participant = json.readTree(mvc.perform(as(get("/api/console/sessions/" + session), MANAGER, "MANAGER", COMPANY))
                .andReturn().getResponse().getContentAsString()).path("participants").get(0).path("id").asLong();

        Instant at = Instant.now();
        long attempt = attempts.save(Attempt.live(participant, 3L, "claude-haiku-5-5", at)).getId();
        // AI 채팅: 질문 턴은 세지 않고, AI 답 2번(입력 100만·출력 20만 토큰)
        turns.save(new ChatTurn(attempt, 1, "USER", "질문", null, null, null, at, null, null, null));
        turns.save(new ChatTurn(attempt, 2, "AI", "답1", "claude-haiku-5-5", 400_000, 100_000, at, "claude", null, 10));
        turns.save(new ChatTurn(attempt, 3, "USER", "질문2", null, null, null, at, null, null, null));
        turns.save(new ChatTurn(attempt, 4, "AI", "답2", "claude-haiku-5-5", 600_000, 100_000, at, "claude", null, 10));
        // 채점: terra 2번(입력 100만·출력 10만, 캐시 30만) + 단가 없는 모델 1번
        long scoring = scorings.save(new Scoring(attempt, catalog.versionId(prompts.active()), at)).getId();
        judgeCalls.save(new JudgeCall(scoring, "gpt-6.1-terra", 500_000, 50_000, 300_000));
        judgeCalls.save(new JudgeCall(scoring, "gpt-6.1-terra", 500_000, 50_000, 0));
        judgeCalls.save(new JudgeCall(scoring, "mystery-model", 10, 10, 0));
    }

    @Test
    void sumsTokensAndCostPerModelForAdmin() throws Exception {
        mvc.perform(as(get("/api/console/sessions/" + session + "/ai-cost"), 1, "ADMIN", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(1))
                // 채팅: 100만 × 0.10 + 20만 × 0.50 = 0.20, 채점: 100만 × 1.0 + 10만 × 5.0 = 1.50
                .andExpect(jsonPath("$.chat.calls").value(2))
                .andExpect(jsonPath("$.chat.inputTokens").value(1_000_000))
                .andExpect(jsonPath("$.chat.outputTokens").value(200_000))
                .andExpect(jsonPath("$.chat.usd").value(0.2))
                .andExpect(jsonPath("$.scoring.calls").value(3))
                .andExpect(jsonPath("$.scoring.cachedTokens").value(300_000))
                .andExpect(jsonPath("$.scoring.usd").value(1.5))
                .andExpect(jsonPath("$.scoring.models[0].model").value("gpt-6.1-terra"))
                .andExpect(jsonPath("$.totalUsd").value(1.7))
                .andExpect(jsonPath("$.perAttemptUsd").value(1.7))
                .andExpect(jsonPath("$.unpricedModels[0]").value("mystery-model"));
    }

    @Test
    void emptySessionCostsNothing() throws Exception {
        mvc.perform(as(get("/api/console/sessions/" + emptySession + "/ai-cost"), 1, "ADMIN", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.totalUsd").value(0.0))
                .andExpect(jsonPath("$.perAttemptUsd").doesNotExist())
                .andExpect(jsonPath("$.chat.models").isEmpty());
    }

    @Test
    void onlyAdminCanSeeCost() throws Exception {
        String url = "/api/console/sessions/" + session + "/ai-cost";
        mvc.perform(as(get(url), MANAGER, "MANAGER", COMPANY)).andExpect(status().isForbidden());
        mvc.perform(as(get(url), 52, "PROCTOR", COMPANY)).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
}
