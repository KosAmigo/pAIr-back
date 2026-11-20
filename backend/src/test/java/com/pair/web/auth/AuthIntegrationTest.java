package com.pair.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
