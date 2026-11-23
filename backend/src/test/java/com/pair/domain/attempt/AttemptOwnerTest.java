package com.pair.domain.attempt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.service.AttemptRegistry;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.repository.AttemptStore;
import com.pair.domain.task.repository.TaskRecordRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.dto.AuthDtos;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 응시 하나를 다루는 요청(/api/attempts/{id}/**)은 자가진단 응시의 주인만 보낼 수 있다 */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class AttemptOwnerTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptRegistry registry;
    @Autowired AttemptStore store;
    @Autowired TaskRecordRepository tasks;
    @MockitoBean JavaMailSender mail; // 가입은 이메일 인증 코드가 필요해 메일을 가짜로 받는다

    AuthDtos.AuthResponse owner;
    AuthDtos.AuthResponse other;
    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        owner = signup();
        other = signup();
        id = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
    }

    private AuthDtos.AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
    }

    /** 응시 하나를 다루는 모든 엔드포인트 (본문은 소유자 확인이 먼저라 형식만 맞으면 된다) */
    private List<RequestBuilder> endpoints(String attemptId, String token) {
        String bearer = token == null ? null : "Bearer " + token;
        var json = MediaType.APPLICATION_JSON;
        return List.of(
                with(get("/api/attempts/{id}", attemptId), bearer),
                with(get("/api/attempts/{id}/file", attemptId).param("path", "x"), bearer),
                with(put("/api/attempts/{id}/file", attemptId).param("path", "x").contentType(json).content("{\"content\":\"\"}"), bearer),
                with(post("/api/attempts/{id}/build", attemptId), bearer),
                with(post("/api/attempts/{id}/run", attemptId), bearer),
                with(post("/api/attempts/{id}/chat", attemptId).contentType(json).content("{\"message\":\"hi\"}"), bearer),
                with(post("/api/attempts/{id}/edits", attemptId).contentType(json).content("{\"turn\":1,\"index\":1,\"action\":\"reject\"}"), bearer),
                with(post("/api/attempts/{id}/submit", attemptId), bearer),
                with(post("/api/attempts/{id}/followups", attemptId).contentType(json).content("{\"answers\":[]}"), bearer),
                with(post("/api/attempts/{id}/rescore", attemptId), bearer),
                with(get("/api/attempts/{id}/report", attemptId), bearer),
                with(get("/api/attempts/{id}/questions", attemptId), bearer),
                with(put("/api/attempts/{id}/questions/1/answer", attemptId).contentType(json).content("{\"text\":\"1\"}"), bearer),
                with(get("/api/attempts/{id}/checkpoints", attemptId), bearer),
                with(get("/api/attempts/{id}/tree", attemptId), bearer));
    }

    private static RequestBuilder with(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String bearer) {
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    @Test
    void 로그인하지_않으면_모든_응시_요청이_401() throws Exception {
        for (RequestBuilder r : endpoints(id, null)) mvc.perform(r).andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_회원의_응시는_모든_요청이_403() throws Exception {
        for (RequestBuilder r : endpoints(id, other.token())) mvc.perform(r).andExpect(status().isForbidden());
    }

    @Test
    void 주인은_자기_응시를_볼_수_있다() throws Exception {
        mvc.perform(get("/api/attempts/{id}", id).header("Authorization", "Bearer " + owner.token())).andExpect(status().isOk());
        mvc.perform(get("/api/attempts/{id}/checkpoints", id).header("Authorization", "Bearer " + owner.token())).andExpect(status().isOk());
    }

    @Test
    void 없는_응시는_404() throws Exception {
        mvc.perform(get("/api/attempts/{id}", "999999").header("Authorization", "Bearer " + owner.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/attempts/{id}", "abc").header("Authorization", "Bearer " + owner.token())).andExpect(status().isNotFound());
    }

    @Test
    void 실전_응시는_로그인_없이_초대_토큰으로_들어오므로_이_검사를_하지_않는다() throws Exception {
        Instant now = Instant.now();
        String live = registry.save(AttemptRecord.live(1L, 3L, "test", now)).getId().toString();
        store.create(new Attempt(live, TestTasks.SELF_DEV, "openai", "test", now, null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null), Map.of());
        mvc.perform(get("/api/attempts/{id}", live)).andExpect(status().isOk());
    }
}
