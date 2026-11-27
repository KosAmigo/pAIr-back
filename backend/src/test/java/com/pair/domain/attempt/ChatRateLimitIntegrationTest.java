package com.pair.domain.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.infra.ai.AssistantClient;

/** AI 채팅 분당 제한(1인 1분 10번): 넘으면 스트림을 열지 않고 429 + Retry-After, AI 도 부르지 않는다 */
class ChatRateLimitIntegrationTest extends BearerIntegrationTest {
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired TaskRepository tasks;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seed() {
        TestTasks.seed(tasks);
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1));
    }

    private AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
    }

    private org.springframework.test.web.servlet.ResultActions chat(String path, String id, AuthResponse user) throws Exception {
        return mvc.perform(post(path, id).header("Authorization", "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"));
    }

    @Test
    void eleventhChatInAMinuteIsRefusedBeforeTheStreamOpens() throws Exception {
        var user = signup();
        String id = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        for (int i = 0; i < 10; i++) chat("/api/attempts/{id}/chat", id, user).andExpect(status().isOk());

        var refused = chat("/api/attempts/{id}/chat/stream", id, user)
                .andExpect(status().isTooManyRequests())
                .andExpect(request().asyncNotStarted())
                .andExpect(header().string("Retry-After", Matchers.matchesPattern("[1-9][0-9]?")))
                .andExpect(jsonPath("$.message", Matchers.containsString("1분에 10번")))
                .andReturn();
        assertThat(refused.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        chat("/api/attempts/{id}/chat", id, user).andExpect(status().isTooManyRequests());
        verify(assistant, never()).stream(any(), any());
        assertThat(store.turns(id)).hasSize(20);

        // 다른 회원은 따로 센다
        var other = signup();
        String otherId = attempts.startSelf(TestTasks.SELF_DEV, other.user().id()).id();
        chat("/api/attempts/{id}/chat", otherId, other).andExpect(status().isOk());
    }
}
