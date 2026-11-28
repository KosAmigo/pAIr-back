package com.pair.domain.attempt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.ChatAttachmentService.Ref;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.infra.ai.AssistantClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * E6: 과제에 저장된 AI 제한(settings 의 aiTurns·aiMaxAttach·aiContextChars)을 응시 중 어시스턴트가 쓴다.
 * 제한이 없는 옛 과제는 전역 설정(pair.assistant.*)으로 동작한다. 응시 화면용 aiLimits 도 같은 값을 돌려준다.
 */
class AiLimitsAttemptTest extends BearerIntegrationTest {

    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired AuthService auth;
    @Autowired TaskRepository taskRecords;
    @Autowired TaskFileRepository taskFiles;
    @Autowired TaskQuestionRepository taskQuestions;
    @Autowired MockMvc mvc;

    private static final ObjectMapper JSON = new ObjectMapper();

    String token;
    Long userId;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        var signup = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test");
        userId = signup.user().id();
        token = signup.token();
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("답", "test", 10, 20, 0, "stop", 1));
    }

    @AfterEach
    void restore() {
        TestTasks.seed(taskRecords); // 다른 테스트가 보는 과제 설정을 원래대로
    }

    /** SELF_DEV 과제 settings 에 AI 제한을 넣는다 */
    private void limit(int turns, int attach, int chars) throws Exception {
        Task t = taskRecords.findByCode(TestTasks.SELF_DEV).orElseThrow();
        ObjectNode s = (ObjectNode) JSON.readTree(t.getSettings());
        s.put("aiTurns", turns).put("aiMaxAttach", attach).put("aiContextChars", chars);
        t.setContent(t.getDescription(), t.getRequirements(), JSON.writeValueAsString(s));
        taskRecords.save(t);
    }

    private static Ref file(String path) {
        return new Ref("ATTEMPT_FILE", path, null);
    }

    @Test
    void 과제의_턴_한도를_넘으면_거절한다() throws Exception {
        limit(2, 3, 100_000);
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        attempts.chat(id, "하나", List.of(), null);
        attempts.chat(id, "둘", List.of(), null);
        assertThatThrownBy(() -> attempts.chat(id, "셋", List.of(), null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("한도(2회)");
    }

    @Test
    void 과제의_한_번에_첨부_개수를_넘으면_AI를_부르기_전에_거절한다() throws Exception {
        limit(10, 1, 100_000);
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        List<String> names = new ArrayList<>(store.readFiles(id).keySet());

        assertThatThrownBy(() -> attempts.chat(id, "q", List.of(file(names.get(0)), file(names.get(1))), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1개");
        verify(assistant, never()).send(any());
        attempts.chat(id, "q", List.of(file(names.get(0))), null); // 1개는 된다
    }

    @Test
    void 과제의_첨부_맥락_글자_합계를_넘으면_거절한다() throws Exception {
        limit(10, 3, 50);
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        String name = new ArrayList<>(store.readFiles(id).keySet()).get(0);
        store.writeWorkspaceFile(id, name, "가".repeat(51));

        assertThatThrownBy(() -> attempts.chat(id, "q", List.of(file(name)), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50");
        store.writeWorkspaceFile(id, name, "가".repeat(50));
        attempts.chat(id, "q", List.of(file(name)), null); // 정확히 한도는 받는다
    }

    @Test
    void 제한이_없는_과제는_전역_설정으로_동작하고_응시_화면에_그_값을_준다() throws Exception {
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        mvc.perform(get("/api/attempts/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiLimits.turns").value(40)).andExpect(jsonPath("$.aiLimits.maxAttach").value(5))
                .andExpect(jsonPath("$.aiLimits.contextChars").value(30000)).andExpect(jsonPath("$.task.aiLimits").doesNotExist());
    }

    @Test
    void 응시_화면에_과제의_제한을_준다() throws Exception {
        limit(10, 2, 8000);
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        mvc.perform(get("/api/attempts/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiLimits.turns").value(10)).andExpect(jsonPath("$.aiLimits.maxAttach").value(2))
                .andExpect(jsonPath("$.aiLimits.contextChars").value(8000));
    }
}
