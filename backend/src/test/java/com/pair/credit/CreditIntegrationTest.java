package com.pair.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.ai.AiUnavailableException;
import com.pair.ai.AssistantClient;
import com.pair.attempt.AttemptService;
import com.pair.attempt.AttemptStore;
import com.pair.attempt.Attempt;
import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptRegistry;
import com.pair.attempt.CheckpointService;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.AuthAccount;
import com.pair.web.auth.AuthAccountRepository;
import com.pair.web.auth.AuthDtos;
import com.pair.web.auth.AuthService;

@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class CreditIntegrationTest {
    @Autowired AuthService auth;
    @Autowired AuthAccountRepository accounts;
    @Autowired CreditService credits;
    @Autowired CreditLedgerRepository ledger;
    @Autowired AttemptService attempts;
    @Autowired AttemptRegistry registry;
    @Autowired AttemptStore store;
    @Autowired CheckpointService checkpoints;
    @Autowired TaskRecordRepository tasks;
    @Autowired MockMvc mvc;
    @MockitoBean AssistantClient assistant;

    @BeforeEach
    void seed() {
        TestTasks.seed(tasks);
    }

    private AuthDtos.AuthResponse signup() {
        return auth.signup(new AuthDtos.SignupRequest("Test", UUID.randomUUID() + "@example.com", "password123"));
    }

    @Test
    void signupStartAndSuccessfulChatHaveLedgerEntries() throws Exception {
        var user = signup();
        long userId = user.user().id();
        assertThat(credits.balance(userId)).isEqualTo(200);
        mvc.perform(get("/api/credits").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(200));
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        assertThat(credits.balance(userId)).isEqualTo(190);
        CreditLedger start = ledger.findFirstByUserIdOrderByIdDesc(userId).orElseThrow();
        assertThat(start.getReason()).isEqualTo("ATTEMPT_START");
        assertThat(start.getAttemptId()).isEqualTo(Long.valueOf(id));
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1));
        mvc.perform(post("/api/attempts/{id}/chat", id)
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk());
        assertThat(credits.balance(userId)).isEqualTo(180);
        CreditLedger chat = ledger.findFirstByUserIdOrderByIdDesc(userId).orElseThrow();
        assertThat(chat.getReason()).isEqualTo("CHAT_TURN");
        assertThat(chat.getAmount()).isEqualTo(-10);
        assertThat(chat.getTurnId()).isNotNull();
        assertThat(store.turns(id)).hasSize(2);
        assertThat(checkpoints.list(id)).extracting(CheckpointService.View::turnNo).containsExactly(1);
    }

    @Test
    void failedOrUnauthorizedChatDoesNotCharge() throws Exception {
        var user = signup();
        var other = signup();
        String id = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        mvc.perform(post("/api/attempts/{id}/chat", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/attempts/{id}/chat/stream", id)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
        verify(assistant, never()).send(any());
        when(assistant.send(any())).thenThrow(new AiUnavailableException("failure"));
        assertThatThrownBy(() -> attempts.chat(id, "hello")).isInstanceOf(AiUnavailableException.class);
        assertThat(credits.balance(user.user().id())).isEqualTo(190);
        assertThat(store.turns(id)).isEmpty();
        assertThat(checkpoints.list(id)).isEmpty();
    }

    @Test
    void emptyReplyKeepsFailureEventWithoutCharge() {
        var user = signup();
        String id = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("", "test", 10, 20, 0, "length", 1));
        assertThatThrownBy(() -> attempts.chat(id, "hello")).isInstanceOf(AiUnavailableException.class);
        assertThat(credits.balance(user.user().id())).isEqualTo(190);
        assertThat(store.turns(id)).isEmpty();
        assertThat(store.events(id)).anyMatch(e -> e.path("type").asText().equals("chatEmpty"));
    }

    @Test
    void streamingResponseIsChargedOnce() {
        var user = signup();
        String id = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        doAnswer(call -> {
            java.util.function.Consumer<String> onDelta = call.getArgument(1);
            onDelta.accept("answer");
            return new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1);
        }).when(assistant).stream(any(), any());
        var chunks = new ArrayList<String>();
        attempts.chat(id, "hello", chunks::add);
        assertThat(chunks).containsExactly("answer");
        assertThat(credits.balance(user.user().id())).isEqualTo(180);
        assertThat(store.turns(id)).hasSize(2);
    }

    @Test
    void streamingEndpointChargesOwner() throws Exception {
        var user = signup();
        String id = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        doAnswer(call -> {
            java.util.function.Consumer<String> onDelta = call.getArgument(1);
            onDelta.accept("answer");
            return new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1);
        }).when(assistant).stream(any(), any());
        var result = mvc.perform(post("/api/attempts/{id}/chat/stream", id)
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:delta", "event:done");
        assertThat(credits.balance(user.user().id())).isEqualTo(180);
    }

    @Test
    void liveAttemptChatRemainsUncharged() {
        Instant now = Instant.now();
        AttemptRecord record = registry.save(AttemptRecord.live(1L, 3L, "test", now));
        String id = record.getId().toString();
        store.create(new Attempt(id, TestTasks.SELF_DEV, "openai", "test", now, null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null), Map.of());
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1));
        long before = ledger.count();
        attempts.chat(id, "hello");
        assertThat(store.turns(id)).hasSize(2);
        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void existingAccountHasNoGrantAndCannotStart() {
        AuthAccount old = accounts.saveAndFlush(new AuthAccount(UUID.randomUUID() + "@example.com", "Old", "unused"));
        assertThat(credits.balance(old.getId())).isZero();
        assertThatThrownBy(() -> attempts.startSelf(TestTasks.SELF_DEV, old.getId()))
                .isInstanceOf(InsufficientCreditException.class);
        assertThat(ledger.findFirstByUserIdOrderByIdDesc(old.getId())).isEmpty();
    }

    @Test
    void insufficientBalancePreventsCallingAiAndCreatingAttempt() throws Exception {
        var user = signup();
        long userId = user.user().id();
        String id = null;
        for (int i = 0; i < 20; i++) id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        String attemptId = id;
        assertThat(credits.balance(userId)).isZero();
        assertThatThrownBy(() -> attempts.chat(attemptId, "hello")).isInstanceOf(InsufficientCreditException.class);
        assertThatThrownBy(() -> attempts.startSelf(TestTasks.SELF_DEV, userId)).isInstanceOf(InsufficientCreditException.class);
        mvc.perform(post("/api/attempts").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":\"order-deadlock\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("크레딧이 부족")));
        verify(assistant, never()).send(any());
        assertThat(store.turns(attemptId)).isEmpty();
    }

    @Test
    void simultaneousStartsCannotOverspend() throws Exception {
        var user = signup();
        long userId = user.user().id();
        for (int i = 0; i < 19; i++) attempts.startSelf(TestTasks.SELF_DEV, userId);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                try {
                    attempts.startSelf(TestTasks.SELF_DEV, userId);
                    return true;
                } catch (InsufficientCreditException e) {
                    return false;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(results.get(0).get(30, TimeUnit.SECONDS) ^ results.get(1).get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(credits.balance(userId)).isZero();
    }
}
