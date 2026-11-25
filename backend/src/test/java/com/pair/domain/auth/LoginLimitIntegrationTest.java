package com.pair.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pair.domain.auth.service.AuthService;
import com.pair.global.config.BearerIntegrationTest;

/** 로그인 API 가 실패 5번 뒤 429 와 Retry-After 를 주고, 꾸민 X-Forwarded-For 로 피할 수 없다 */
class LoginLimitIntegrationTest extends BearerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    private ResultActions login(String email, String password, String forwarded) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", forwarded)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void blocksAfterFiveFailuresEvenWithSpoofedForwardedHeader() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        AuthTestSupport.signup(auth, mail, "회원", email);
        String ip = "198.51.100.21";
        for (int i = 0; i < 5; i++) login(email, "wrong-password", "10.9.9." + i + ", " + ip).andExpect(status().isUnauthorized());

        login(email, "password123", "10.9.9.99, " + ip).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.message").isNotEmpty());
        // 다른 곳에서 접속한 주인은 로그인할 수 있다
        login(email, "password123", "198.51.100.22").andExpect(status().isOk());
    }

    @Test
    void unknownAccountsAreLimitedTheSameWay() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) login(email, "wrong-password", "198.51.100.23").andExpect(status().isUnauthorized());
        login(email, "wrong-password", "198.51.100.23").andExpect(status().isTooManyRequests());
    }
}
