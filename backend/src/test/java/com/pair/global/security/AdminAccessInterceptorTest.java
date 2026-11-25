package com.pair.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.global.config.BearerIntegrationTest;

/** 채점 툴(/api/grader/**)은 관리자만 쓸 수 있다: 로그인 안 함 401, 관리자가 아니면 403 */
class AdminAccessInterceptorTest extends BearerIntegrationTest {

    private static final long COMPANY_ID = 41007L;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    AuthResponse member;

    @BeforeEach
    void setUp() {
        member = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com");
    }

    private void becomes(String role) {
        boolean company = role.equals("MANAGER") || role.equals("PROCTOR");
        jdbc.update("update users set role = ?, company_id = ? where user_id = ?", role, company ? COMPANY_ID : null, member.user().id());
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/grader/rubric", "GET,/api/grader/versions", "GET,/api/grader/versions/x", "POST,/api/grader/versions",
            "PUT,/api/grader/active", "GET,/api/grader/attempts", "POST,/api/grader/estimate", "POST,/api/grader/runs",
            "GET,/api/grader/runs", "GET,/api/grader/runs/1", "POST,/api/grader/runs/1/cancel",
            "GET,/api/grader/runs/1/results/a.json", "GET,/api/grader/runs/1/export" })
    void everyGraderEndpointRejectsAnonymousAndMembers(String method, String path) throws Exception {
        var anonymous = request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}");
        mvc.perform(anonymous).andExpect(status().isUnauthorized());

        var signed = request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer " + member.token());
        mvc.perform(signed).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "MANAGER", "PROCTOR" })
    void companyAccountsAreRejected(String role) throws Exception {
        becomes(role);
        mvc.perform(request(HttpMethod.GET, "/api/grader/rubric").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedDevHeadersAndInvalidTokensAreRejected() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/grader/rubric").header("X-Pair-User-Id", "1").header("X-Pair-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(HttpMethod.GET, "/api/grader/rubric").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminIsAllowed() throws Exception {
        becomes("ADMIN");
        mvc.perform(request(HttpMethod.GET, "/api/grader/rubric").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk());
    }
}
