package com.pair.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.ai.AssistantClient;
import com.pair.attempt.ChatAttachmentService.Ref;
import com.pair.attempt.ChatTurn.AssistantRequest;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskFileRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.AuthService;
import com.pair.web.auth.AuthTestSupport;

/** 응시 전체의 첨부 본문 합계가 한도를 넘으면 오래된 첨부부터 한 줄 표지로 줄이고, 질문은 막히지 않는다 */
@SpringBootTest(properties = "pair.assistant.max-attachment-context-chars=2000")
@ActiveProfiles("examtest")
class ChatAttachmentContextLimitTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired AttemptService attempts;
    @Autowired AttemptStore store;
    @Autowired AuthService auth;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;
    @MockitoBean AssistantClient assistant;
    @MockitoBean JavaMailSender mail;

    @Test
    void 한도를_넘으면_오래된_첨부는_표지로_줄고_이번_첨부는_항상_본문으로_간다() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        Long userId = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test").user().id();
        String id = attempts.startSelf(TestTasks.SELF_DEV, userId).id();
        List<String> names = new ArrayList<>(store.readFiles(id).keySet());
        store.writeWorkspaceFile(id, names.get(0), "가".repeat(1500));
        store.writeWorkspaceFile(id, names.get(1), "나".repeat(1500));
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("답", "test", 10, 20, 0, "stop", 1));

        attempts.chat(id, "첫 질문", List.of(new Ref("ATTEMPT_FILE", names.get(0), null)), null);
        attempts.chat(id, "둘째", List.of(new Ref("ATTEMPT_FILE", names.get(1), null)), null);

        ArgumentCaptor<AssistantRequest> c = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistant, atLeastOnce()).send(c.capture());
        var msgs = c.getValue().messages();
        assertThat(msgs.get(0).content()).contains("[첨부: " + names.get(0) + " - 자료가 쌓여 용량 한도를 넘어 생략함]").doesNotContain("가가가");
        assertThat(msgs.get(msgs.size() - 1).content()).contains("나".repeat(1500));
    }
}
