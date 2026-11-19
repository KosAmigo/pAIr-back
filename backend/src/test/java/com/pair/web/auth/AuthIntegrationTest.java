package com.pair.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 가입·로그인·로그아웃과 토큰 기반 권한 확인 흐름을 H2 DB로 검증한다. */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void signupLoginMeAndLogout() throws Exception {
        MvcResult signup = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"테스트 사용자","email":"Test@Example.com","password":"secure-pass-123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();
        JsonNode signupBody = json.readTree(signup.getResponse().getContentAsString());
        String signupToken = signupBody.path("token").asText();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(signupBody.path("user").path("id").asLong()));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"TEST@example.com","password":"secure-pass-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"));

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateSignupAndWrongPassword() throws Exception {
        String body = """
                {"name":"테스트 사용자","email":"duplicate@example.com","password":"secure-pass-123"}
                """;
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"duplicate@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void consoleIdentityComesFromTokenInsteadOfCallerSuppliedHeaders() throws Exception {
        mvc.perform(get("/api/console/sessions")
                        .header(HeaderCurrentUserResolver.USER_ID, "1")
                        .header(HeaderCurrentUserResolver.ROLE, "ADMIN"))
                .andExpect(status().isUnauthorized());

        MvcResult signup = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"응시자","email":"token-user@example.com","password":"secure-pass-123"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(signup.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/api/console/sessions")
                        .header("Authorization", "Bearer " + token)
                        .header(HeaderCurrentUserResolver.USER_ID, "1")
                        .header(HeaderCurrentUserResolver.ROLE, "ADMIN"))
                .andExpect(status().isForbidden());
    }
}
