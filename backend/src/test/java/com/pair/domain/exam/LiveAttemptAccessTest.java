package com.pair.domain.exam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.global.security.AttemptOwnerInterceptor;
import com.pair.global.security.InviteTokenService;

/** 실전 응시는 번호만으로 열리지 않는다: 그 응시의 초대 토큰을 가진 응시자, 리포트는 그 회사의 매니저와 관리자만 */
class LiveAttemptAccessTest extends BearerIntegrationTest {

    private static final long COMPANY = 41008L;
    private static final long OTHER_COMPANY = 41009L;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptRegistryService registry;
    @Autowired AttemptStateRepository store;
    @Autowired ExamSessionRepository sessions;
    @Autowired ExamParticipantRepository participants;
    @Autowired TaskRepository tasks;
    @Autowired JdbcTemplate jdbc;

    ExamSession session;
    ExamParticipant participant;
    String invite;
    String otherInvite;
    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        Instant now = Instant.now();
        session = sessions.save(new ExamSession(COMPANY, "회차", now.minusSeconds(60), now.plusSeconds(3600), false, List.of(3L), 1L, now));
        invite = InviteTokenService.next();
        otherInvite = InviteTokenService.next();
        participant = participant(invite);
        participant(otherInvite);
        id = registry.save(Attempt.live(participant.getId(), 3L, "test", now)).getId().toString();
        store.create(new AttemptStateDto(id, TestTasks.SELF_DEV, "openai", "test", now, null, null, null,
                AttemptStateDto.Status.SOLVING, List.of(), null, null), Map.of("src/A.java", "class A {}"));
    }

    private ExamParticipant participant(String token) {
        return participants.save(new ExamParticipant(session, UUID.randomUUID() + "@example.com", "응시자",
                InviteTokenService.hash(token), Instant.now()));
    }

    /** 이 역할로 로그인한 토큰 */
    private String login(String role, Long company) {
        AuthResponse user = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com");
        jdbc.update("update users set role = ?, company_id = ? where user_id = ?", role, company, user.user().id());
        return "Bearer " + user.token();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(AttemptOwnerInterceptor.INVITE, token);
    }

    /** 응시를 다루는 모든 경로. 본문은 주인 확인이 먼저라 형식만 맞으면 된다 */
    private MockHttpServletRequestBuilder call(String method, String path) {
        return MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/attempts/" + id + path)
                .param("path", "src/A.java").param("with", "1").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "GET|", "GET|/file", "PUT|/file", "POST|/build", "POST|/run", "POST|/diagnostics", "POST|/complete", "POST|/definition",
            "POST|/chat", "POST|/chat/stream", "GET|/attachments/1", "POST|/edits", "GET|/questions", "PUT|/questions/1/answer",
            "GET|/materials", "GET|/materials/file", "GET|/materials/pages", "POST|/submit", "POST|/followups",
            "POST|/rescore", "GET|/report", "GET|/checkpoints", "GET|/tree", "GET|/compare", "GET|/compare/files" }, delimiter = '|')
    void 번호만_알거나_다른_응시자의_토큰이면_모두_막는다(String method, String path) throws Exception {
        String p = path == null ? "" : path;
        mvc.perform(call(method, p)).andExpect(status().isUnauthorized());
        mvc.perform(call(method, p).header("Authorization", login("USER", null))).andExpect(status().isForbidden());
        boolean report = p.equals("/report");
        for (String bad : List.of(otherInvite, InviteTokenService.next(), "x")) {
            // 리포트는 토큰이 아니라 로그인으로만 열린다
            mvc.perform(as(call(method, p), bad)).andExpect(report ? status().isUnauthorized() : status().isForbidden());
        }
    }

    @Test
    void 응시자는_자기_응시를_풀_수_있다() throws Exception {
        mvc.perform(as(get("/api/attempts/{id}", id), invite)).andExpect(status().isOk());
        mvc.perform(as(get("/api/attempts/{id}/file", id).param("path", "src/A.java"), invite)).andExpect(status().isOk());
        mvc.perform(as(put("/api/attempts/{id}/file", id).param("path", "src/A.java").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"class A { }\"}"), invite)).andExpect(status().isOk());
        mvc.perform(as(get("/api/attempts/{id}/questions", id), invite)).andExpect(status().isOk());
        mvc.perform(as(get("/api/attempts/{id}/materials", id), invite)).andExpect(status().isOk());
        mvc.perform(as(post("/api/attempts/{id}/submit", id), invite)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "PUT|/file", "POST|/build", "POST|/run", "POST|/diagnostics", "POST|/complete", "POST|/definition", "POST|/chat",
            "GET|/attachments/1", "POST|/edits", "PUT|/questions/1/answer", "GET|/materials/file", "GET|/materials/pages",
            "POST|/followups" }, delimiter = '|')
    void 풀이에_쓰는_경로는_주인_토큰이면_권한_검사를_통과한다(String method, String path) throws Exception {
        mvc.perform(as(call(method, path), invite)).andExpect(status().is(Matchers.not(Matchers.in(List.of(401, 403)))));
    }

    @Test
    void 다른_계정으로_로그인돼_있어도_초대_토큰이_맞으면_풀_수_있다() throws Exception {
        mvc.perform(as(get("/api/attempts/{id}", id), invite).header("Authorization", login("USER", null))).andExpect(status().isOk());
    }

    @Test
    void 응시자는_리포트_다시_채점_복기를_쓸_수_없다() throws Exception {
        mvc.perform(as(get("/api/attempts/{id}/report", id), invite)).andExpect(status().isUnauthorized());
        for (String path : List.of("/checkpoints", "/tree", "/compare", "/compare/files")) {
            mvc.perform(as(call("GET", path), invite)).andExpect(status().isForbidden());
        }
        mvc.perform(as(post("/api/attempts/{id}/rescore", id), invite)).andExpect(status().isForbidden());
    }

    @Test
    void 링크를_다시_발급하면_옛_토큰은_막힌다() throws Exception {
        String renewed = InviteTokenService.next();
        participant.reissueInvite(InviteTokenService.hash(renewed), Instant.now());
        participants.save(participant);
        mvc.perform(as(get("/api/attempts/{id}", id), invite)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/attempts/{id}", id), renewed)).andExpect(status().isOk());
    }

    @Test
    void 리포트는_그_회사의_매니저와_관리자만_본다() throws Exception {
        mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", login("MANAGER", COMPANY))).andExpect(status().isOk());
        mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", login("ADMIN", null))).andExpect(status().isOk());
        mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", login("PROCTOR", COMPANY))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", login("USER", null))).andExpect(status().isForbidden());
        // 다른 회사에는 있는지조차 알리지 않는다
        mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", login("MANAGER", OTHER_COMPANY))).andExpect(status().isNotFound());
    }

    @Test
    void 매니저와_관리자도_응시자_대신_풀이를_건드릴_수_없다() throws Exception {
        for (String token : List.of(login("MANAGER", COMPANY), login("ADMIN", null))) {
            mvc.perform(get("/api/attempts/{id}", id).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/attempts/{id}/submit", id).header("Authorization", token)).andExpect(status().isForbidden());
        }
    }

    @Test
    void 초대_토큰으로_남의_자가진단을_열_수_없다() throws Exception {
        AuthResponse user = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com");
        String self = registry.save(Attempt.self(user.user().id(), 1L, "test", Instant.now())).getId().toString();
        mvc.perform(as(get("/api/attempts/{id}", self), invite)).andExpect(status().isUnauthorized());
    }

    @Test
    void 대상자가_없는_실전_응시는_아무_토큰으로도_열리지_않는다() throws Exception {
        String orphan = registry.save(Attempt.live(null, 3L, "test", Instant.now())).getId().toString();
        mvc.perform(as(get("/api/attempts/{id}", orphan), invite)).andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}/report", orphan).header("Authorization", login("ADMIN", null))).andExpect(status().isForbidden());
    }
}
