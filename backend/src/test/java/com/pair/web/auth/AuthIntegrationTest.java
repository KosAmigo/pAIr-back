package com.pair.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 가입·로그인·로그아웃과 토큰 기반 권한 확인 흐름을 H2 DB로 검증한다. */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mail;

    @Test
    void signupLoginMeAndLogout() throws Exception {
        String code = requestVerificationCode("Test@Example.com");
        MvcResult signup = mvc.perform(signup("테스트 사용자", "Test@Example.com", "secure-pass-123", code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();
        JsonNode signupBody = json.readTree(signup.getResponse().getContentAsString());
        String signupToken = signupBody.path("token").asText();
        String refreshToken = signupBody.path("refreshToken").asText();
        assertThat(signupBody.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(signupToken.split("\\.")).hasSize(3);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(signupBody.path("user").path("id").asLong()));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"TEST@example.com","password":"secure-pass-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"));

        MvcResult refreshed = mvc.perform(tokenRequest("/api/auth/refresh", refreshToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode refreshedBody = json.readTree(refreshed.getResponse().getContentAsString());
        String newRefresh = refreshedBody.path("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refreshedBody.path("token").asText()))
                .andExpect(status().isOk());
        mvc.perform(tokenRequest("/api/auth/refresh", refreshToken)).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/logout", refreshToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(tokenRequest("/api/auth/logout", newRefresh))
                .andExpect(status().isNoContent());
        mvc.perform(tokenRequest("/api/auth/refresh", newRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsDuplicateSignupAndWrongPassword() throws Exception {
        String code = requestVerificationCode("duplicate@example.com");
        mvc.perform(signup("테스트 사용자", "duplicate@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated());
        mvc.perform(signup("테스트 사용자", "duplicate@example.com", "secure-pass-123", code))
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

        String code = requestVerificationCode("token-user@example.com");
        MvcResult signup = mvc.perform(signup("응시자", "token-user@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(signup.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/api/console/sessions")
                        .header("Authorization", "Bearer " + token)
                        .header(HeaderCurrentUserResolver.USER_ID, "1")
                        .header(HeaderCurrentUserResolver.ROLE, "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signupRequiresValidEmailVerificationCode() throws Exception {
        mvc.perform(signup("미인증 사용자", "unverified@example.com", "secure-pass-123", "123456"))
                .andExpect(status().isBadRequest());

        String code = requestVerificationCode("verified@example.com");
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", "000000".equals(code) ? "000001" : "000000"))
                .andExpect(status().isBadRequest());
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated());
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", code))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsExpiredTamperedWrongTypeAndLegacyTokens() throws Exception {
        String code = requestVerificationCode("jwt-check@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "jwt-check@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        long userId = body.path("user").path("id").asLong();
        String access = body.path("token").asText();
        String expiredAccess = tokens.access(userId, Instant.now().minus(JwtTokens.ACCESS_LIFETIME).minusSeconds(1));
        String expiredRefresh = tokens.refresh(userId, com.pair.exam.service.InviteTokens.next(),
                Instant.now().minus(JwtTokens.REFRESH_LIFETIME).minusSeconds(1));
        String[] parts = access.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        for (String invalid : new String[] { expiredAccess, tampered, com.pair.exam.service.InviteTokens.next() }) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(tokenRequest("/api/auth/refresh", expiredRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/refresh", access)).andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveAccountCannotUseOrRefreshExistingTokens() throws Exception {
        String code = requestVerificationCode("inactive-jwt@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "inactive-jwt@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        jdbc.update("update users set member_status = ? where user_id = ?", "WITHDRAWN", body.path("user").path("id").asLong());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + body.path("token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/refresh", body.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOneConcurrentRefreshCanConsumeTheSameToken() throws Exception {
        String code = requestVerificationCode("concurrent-jwt@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "concurrent-jwt@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        String refresh = json.readTree(result.getResponse().getContentAsString()).path("refreshToken").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryRefresh(refresh, start));
            var second = executor.submit(() -> tryRefresh(refresh, start));
            start.countDown();
            assertThat(new boolean[] { first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS) })
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private boolean tryRefresh(String token, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            auth.refresh(token);
            return true;
        } catch (UnauthorizedException e) {
            return false;
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
            String path, String refreshToken) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.RefreshRequest(refreshToken)));
    }

    private String requestVerificationCode(String email) throws Exception {
        mvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.EmailVerificationRequest(email))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        clearInvocations(mail);
        Matcher matcher = Pattern.compile("인증 코드: ([0-9]{6})").matcher(message.getValue().getText());
        if (!matcher.find()) throw new AssertionError("인증 코드가 메일 본문에 없습니다.");
        return matcher.group(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(
            String name, String email, String password, String code) throws Exception {
        return post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.SignupRequest(name, email, password, code)));
    }
}
