package com.pair.domain.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.repository.CheckpointRepository;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.service.RollbackService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.global.error.ConflictException;
import com.pair.global.error.ForbiddenException;
import com.pair.infra.ai.AssistantClient;

/** 복기에서 지우기: 제출하지 않은 내 자가진단 응시만 지우고, 지운 응시는 어디에서도 보이지 않는다 */
class AttemptDeleteTest extends BearerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptRegistryService registry;
    @Autowired AttemptRepository records;
    @Autowired AttemptStateRepository store;
    @Autowired CheckpointRepository checkpoints;
    @Autowired RollbackService rollbacks;
    @Autowired CreditService credits;
    @Autowired TaskRepository tasks;

    AuthResponse owner;
    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        owner = signup();
        id = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        org.mockito.Mockito.when(assistant.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantClient.Reply("답", "test", 10, 20, 0, "stop", 1));
    }

    private AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
    }

    private String bearer(AuthResponse who) {
        return "Bearer " + who.token();
    }

    private List<String> listed() throws Exception {
        String body = mvc.perform(get("/api/attempts").param("includeEmpty", "true").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$[*].id");
    }

    @Test
    void 지운_응시는_목록과_주소에서_사라지고_크레딧은_그대로다() throws Exception {
        Attempt loadedBefore = records.findById(Long.valueOf(id)).orElseThrow();
        int balance = credits.balance(owner.user().id());

        mvc.perform(delete("/api/attempts/{id}", id).header("Authorization", bearer(owner))).andExpect(status().isOk());

        assertThat(listed()).doesNotContain(id);
        mvc.perform(get("/api/attempts/{id}", id).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(post("/api/attempts/{id}/submit", id).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        assertThat(credits.balance(owner.user().id())).isEqualTo(balance);
        // 지우기 전에 읽어 둔 엔티티를 저장해도 삭제 표시는 남는다
        records.save(loadedBefore);
        assertThat(records.findById(Long.valueOf(id)).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 남의_응시는_지울_수_없다() throws Exception {
        mvc.perform(delete("/api/attempts/{id}", id).header("Authorization", bearer(signup()))).andExpect(status().isForbidden());
        assertThat(listed()).contains(id);
    }

    @Test
    void 제출을_마친_응시는_지울_수_없다() throws Exception {
        var t = Instant.now();
        store.save(store.load(id).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(AttemptStateDto.Status.DONE));
        mvc.perform(delete("/api/attempts/{id}", id).header("Authorization", bearer(owner))).andExpect(status().isConflict());
        assertThat(listed()).contains(id);
    }

    @Test
    void 갈래가_있으면_막고_갈래를_지우면_지울_수_있다() throws Exception {
        attempts.chat(id, "질문", List.of(), null);
        Long checkpointId = checkpoints.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        String fork = rollbacks.rollback(owner.user().id(), checkpointId).attemptId();
        assertThatThrownBy(() -> registry.delete(id)).isInstanceOf(ConflictException.class);

        registry.delete(fork);
        mvc.perform(get("/api/attempts/{id}/tree", id).header("Authorization", bearer(owner))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
        registry.delete(id);
        assertThatThrownBy(() -> rollbacks.rollback(owner.user().id(), checkpointId)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void 실전_응시는_지울_수_없다() {
        // 실전 응시자는 초대 토큰으로 /api/attempts/{id} 를 통과하므로 서비스가 막아야 한다
        Long taskId = tasks.findByCode(TestTasks.SELF_DEV).orElseThrow().getId();
        Attempt live = records.save(Attempt.live(System.nanoTime(), taskId, "m", Instant.now()));
        assertThatThrownBy(() -> registry.delete(String.valueOf(live.getId()))).isInstanceOf(ForbiddenException.class);
        records.delete(live);
    }
}
