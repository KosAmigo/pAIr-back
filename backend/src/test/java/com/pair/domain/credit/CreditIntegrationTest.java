package com.pair.domain.credit;

import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.credit.entity.CreditLedger;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.credit.exception.InsufficientCreditException;
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
import org.springframework.mail.javamail.JavaMailSender;

import com.pair.infra.ai.AiUnavailableException;
import com.pair.infra.ai.AssistantClient;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.repository.AttemptStoreRepository;
import com.pair.domain.attempt.service.AttemptState;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.service.AttemptRegistry;
import com.pair.domain.attempt.service.CheckpointService;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

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
    @Autowired AttemptStoreRepository store;
    @Autowired CheckpointService checkpoints;
    @Autowired TaskRepository tasks;
    @Autowired MockMvc mvc;
    @MockitoBean AssistantClient assistant;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void seed() {
        TestTasks.seed(tasks);
    }

    private AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
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
    void purchaseAndLedgerEndpointsRequireOwnerAndExposeHistory() throws Exception {
        var user = signup();
        var other = signup();
        mvc.perform(get("/api/credits/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/credits/products").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].credits").value(1000))
                .andExpect(jsonPath("$[0].amountKrw").value(4990));
        var response = mvc.perform(post("/api/credits/purchases")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"credits\":1000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        String orderId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("orderId").asText();
        mvc.perform(post("/api/credits/purchases/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/credits/purchases/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(get("/api/credits/purchases").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].orderId").value(orderId));
        mvc.perform(get("/api/credits/ledger").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].reason").value("SIGNUP"));
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
        Attempt record = registry.save(Attempt.live(1L, 3L, "test", now));
        String id = record.getId().toString();
        store.create(new AttemptState(id, TestTasks.SELF_DEV, "openai", "test", now, null, null, null,
                AttemptState.Status.SOLVING, List.of(), null, null), Map.of());
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

    @Test
    void aiCallInFlightDoesNotHoldUserLockOrTransaction() throws Exception {
        var user = signup();
        long userId = user.user().id();
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        var inAiCall = new CountDownLatch(1);
        var releaseAi = new CountDownLatch(1);
        when(assistant.send(any())).thenAnswer(invocation -> {
            inAiCall.countDown();
            if (!releaseAi.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
            return new AssistantClient.Reply("answer", "test", 10, 20, 0, "stop", 1);
        });
        try (var executor = Executors.newFixedThreadPool(1)) {
            var chat = executor.submit(() -> attempts.chat(id, "hello"));
            assertThat(inAiCall.await(10, TimeUnit.SECONDS)).isTrue();

            // AI 가 답하는 동안에도 같은 회원의 다른 크레딧 작업(응시 시작)이 막히지 않는다
            attempts.startSelf(TestTasks.SELF_DEV, userId);

            releaseAi.countDown();
            assertThat(chat.get(30, TimeUnit.SECONDS)).hasSize(2);
        }
        // 응시 시작 2번(20) + 채팅 1번(10)
        assertThat(credits.balance(userId)).isEqualTo(200 - 10 - 10 - 10);
    }
}
