package com.pair.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.attempt.db.CheckpointRecordRepository;
import com.pair.run.CodeRunner;
import com.pair.task.TaskFileRecordRepository;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TaskRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.AuthDtos;
import com.pair.web.auth.AuthService;
import com.pair.web.auth.AuthTestSupport;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 롤백으로 갈라진 응시를 실행하면, 실행기(CodeRunner)가 복원된 파일을 받는지 확인한다.
 * 실행기는 응시 파일을 DB 에서 읽어 매번 넘겨 받으므로(LocalJvmRunner·SandboxRunner 모두 상태 없음),
 * 롤백이 attempt_files 를 맞춰 두면 컨테이너 쪽에 따로 할 일이 없다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
class RollbackRunTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @MockitoBean CodeRunner runner;
    @MockitoBean JavaMailSender mail;

    @Autowired AttemptService attempts;
    @Autowired AttemptStore store;
    @Autowired AttemptRegistry registry;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;
    @Autowired TaskRepository tasks;
    @Autowired CheckpointService checkpoints;
    @Autowired RollbackService rollbacks;
    @Autowired CheckpointRecordRepository checkpointRecords;
    @Autowired AuthService auth;

    Long userId;
    String id;
    String javaPath;
    String original;
    String edited;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        // 가입하면 크레딧이 지급된다 (롤백은 크레딧을 차감하므로 필요)
        userId = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test").user().id();
        Map<String, String> files = tasks.visibleFiles(TestTasks.SELF_DEV);
        javaPath = files.keySet().stream().filter(k -> k.startsWith("src/") && k.endsWith(".java")).sorted().findFirst().orElseThrow();
        original = files.get(javaPath);
        edited = original + "\n// 고침";

        id = String.valueOf(registry.save(AttemptRecord.self(userId, 1L, "gpt-test", Instant.now())).getId());
        store.create(new Attempt(id, TestTasks.SELF_DEV, "openai", "gpt-test", Instant.now(), null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null), files);
        checkpoints.appendQuestion(id, new ChatTurn(1, "user", Instant.now(), "질문1", null, null, null, null, null, null), files);
        store.appendTurn(id, new ChatTurn(2, "ai", Instant.now(), "답1", null, null, null, null, null, null));
        Map<String, String> after = new TreeMap<>(files);
        after.put(javaPath, edited);
        store.writeWorkspaceFile(id, javaPath, edited);
        checkpoints.appendQuestion(id, new ChatTurn(3, "user", Instant.now(), "질문3", null, null, null, null, null, null), after);

        when(runner.run(anyMap())).thenReturn(new CodeRunner.RunResult(true, List.of(), 0, 0, List.of(), List.of(), 0));
    }

    @Test
    void 롤백한_응시를_실행하면_복원된_파일이_실행기로_넘어간다() {
        List<Long> cps = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).stream().map(c -> c.getId()).toList();

        // 질문 1 시점(고치기 전)으로 롤백 → 원본이 실행된다
        String first = rollbacks.rollback(userId, cps.get(0)).attemptId();
        assertThat(runAndCapture(first).get(javaPath)).isEqualTo(original);

        // 질문 3 시점(고친 뒤)으로 롤백 → 고친 코드가 실행된다
        String second = rollbacks.rollback(userId, cps.get(1)).attemptId();
        assertThat(runAndCapture(second).get(javaPath)).isEqualTo(edited);

        // 원래 응시의 파일은 그대로(고친 상태)
        assertThat(store.readFiles(id).get(javaPath)).isEqualTo(edited);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> runAndCapture(String attemptId) {
        org.mockito.Mockito.clearInvocations(runner);
        attempts.run(attemptId);
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(captor.capture());
        return captor.getValue();
    }
}
