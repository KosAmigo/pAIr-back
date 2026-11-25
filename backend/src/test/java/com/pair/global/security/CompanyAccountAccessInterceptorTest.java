package com.pair.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;

/** 기업 계정(매니저·감독관)은 자가진단·크레딧의 쓰기만 막히고 조회는 되며, 관리자와 일반 회원은 막히지 않는다 */
class CompanyAccountAccessInterceptorTest extends BearerIntegrationTest {

    private static final long COMPANY_ID = 41006L;
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired TaskRepository tasks;
    @Autowired JdbcTemplate jdbc;

    AuthResponse member;
    String attemptId;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        member = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com");
        // 일반 회원일 때 시작한 자가진단 응시가 있는 계정이, 나중에 기업 계정이 된 경우
        attemptId = attempts.startSelf(TestTasks.SELF_DEV, member.user().id()).id();
    }

    private void becomes(String role) {
        boolean company = role.equals("MANAGER") || role.equals("PROCTOR");
        jdbc.update("update users set role = ?, company_id = ? where user_id = ?", role, company ? COMPANY_ID : null, member.user().id());
    }

    private MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + member.token());
    }

    private MockHttpServletRequestBuilder writeJson(MockHttpServletRequestBuilder request, String body) {
        return signed(request).contentType(JSON).content(body);
    }

    @ParameterizedTest
    @ValueSource(strings = { "MANAGER", "PROCTOR" })
    void companyAccountsCannotStartSelfDiagnosisOrUseCredits(String role) throws Exception {
        becomes(role);

        mvc.perform(writeJson(post("/api/attempts"), "{\"taskId\":\"" + TestTasks.SELF_DEV + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(CompanyAccountAccessInterceptor.DENIED_MESSAGE));
        mvc.perform(writeJson(post("/api/credits/purchases"), "{\"credits\":1000}")).andExpect(status().isForbidden());
        mvc.perform(writeJson(post("/api/credits/purchases/x/confirm"), "{\"paymentKey\":\"k\",\"amount\":4990}"))
                .andExpect(status().isForbidden());
        for (String action : new String[] { "recover", "cancel", "refund" }) {
            mvc.perform(signed(post("/api/credits/purchases/x/" + action))).andExpect(status().isForbidden());
        }
        mvc.perform(signed(post("/api/checkpoints/1/rollback"))).andExpect(status().isForbidden());
        mvc.perform(writeJson(patch("/api/checkpoints/1"), "{\"label\":\"a\"}")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "MANAGER", "PROCTOR" })
    void companyAccountsCannotChangeTheirOldSelfAttempts(String role) throws Exception {
        becomes(role);

        mvc.perform(writeJson(put("/api/attempts/{id}/file", attemptId).param("path", "x"), "{\"content\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(writeJson(post("/api/attempts/{id}/chat", attemptId), "{\"message\":\"hi\"}")).andExpect(status().isForbidden());
        mvc.perform(signed(post("/api/attempts/{id}/build", attemptId))).andExpect(status().isForbidden());
        mvc.perform(signed(post("/api/attempts/{id}/submit", attemptId))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "MANAGER", "PROCTOR" })
    void companyAccountsCanStillReadOldRecords(String role) throws Exception {
        becomes(role);

        for (String path : new String[] { "/api/credits", "/api/credits/products", "/api/credits/ledger", "/api/credits/purchases",
                "/api/attempts", "/api/attempts/" + attemptId, "/api/attempts/" + attemptId + "/checkpoints" }) {
            mvc.perform(signed(get(path))).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "USER", "ADMIN" })
    void adminAndMembersAreNotBlocked(String role) throws Exception {
        becomes(role);

        mvc.perform(writeJson(post("/api/credits/purchases"), "{\"credits\":1000}")).andExpect(status().isOk());
        mvc.perform(writeJson(post("/api/attempts"), "{\"taskId\":\"" + TestTasks.SELF_DEV + "\"}")).andExpect(status().isOk());
        mvc.perform(writeJson(put("/api/attempts/{id}/file", attemptId).param("path", "x"), "{\"content\":\"\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
