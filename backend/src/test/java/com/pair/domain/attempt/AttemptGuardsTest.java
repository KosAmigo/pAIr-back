package com.pair.domain.attempt;

import com.pair.global.config.BearerIntegrationTest;

import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.service.EditProposalService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.infra.ai.AssistantClient;
import com.pair.infra.sandbox.SandboxRunner;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

/** 응시 쪽 방어: 읽기 전용 파일은 저장·AI 적용 모두 막고, 실행 대기열이 꽉 차면 503 */
// 테스트 속도: 컨텍스트를 같이 쓰려고 공통 설정(BearerIntegrationTest)을 상속하도록 바꿈. 설정만 옮겼고 테스트 내용은 그대로
class AttemptGuardsTest extends BearerIntegrationTest {

    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired AuthService auth;
    @Autowired TaskRepository taskRecords;
    @Autowired TaskFileRepository taskFiles;
    @Autowired TaskQuestionRepository taskQuestions;
    @Autowired MockMvc mvc;

    String token;
    String devId;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        var signup = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test");
        token = signup.token();
        devId = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
    }

    private org.springframework.test.web.servlet.ResultActions save(String path, String content) throws Exception {
        return mvc.perform(put("/api/attempts/{id}/file", devId).param("path", path).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"" + content + "\"}"));
    }

    @Test
    void 로그와_자료_경로는_저장할_수_없고_내용도_그대로다() throws Exception {
        String before = store.readFiles(devId).get("logs/app.log");
        assertThat(before).isNotNull();
        save("logs/app.log", "지움").andExpect(status().isBadRequest());
        assertThat(store.readFiles(devId).get("logs/app.log")).isEqualTo(before);
        // 다른 파일은 그대로 저장된다
        save("NOTES.md", "원인 메모").andExpect(status().isOk());
        assertThat(store.readFiles(devId).get("NOTES.md")).isEqualTo("원인 메모");
    }

    @Test
    void 과제에서_읽기_전용으로_정한_파일은_저장할_수_없다() throws Exception {
        String path = "src/shop/Db.java";
        TaskFile original = taskFiles.findByTaskIdAndPath(1L, path).orElseThrow();
        taskFiles.delete(original);
        taskFiles.save(new TaskFile(1L, path, original.getContent(), original.getMimeType(), original.getSizeBytes(),
                original.getVisibility(), true));
        try {
            String before = store.readFiles(devId).get(path);
            save(path, "class Db {}").andExpect(status().isBadRequest());
            assertThat(store.readFiles(devId).get(path)).isEqualTo(before);
        } finally {
            taskFiles.delete(taskFiles.findByTaskIdAndPath(1L, path).orElseThrow());
            taskFiles.save(new TaskFile(1L, path, original.getContent(), original.getMimeType(), original.getSizeBytes(),
                    original.getVisibility(), false));
        }
    }

    @Test
    void AI_수정_제안도_읽기_전용_파일에는_적용되지_않고_적용_기록도_남지_않는다() {
        String before = store.readFiles(devId).get("logs/app.log");
        String firstLine = before.lines().findFirst().orElseThrow();
        // 실제로 적용 가능한 제안(로그 첫 줄 바꾸기)이어야 읽기 전용 검사가 막는지 확인할 수 있다
        String reply = "로그를 고쳐 보세요.\n```edit path=logs/app.log\n<<<<<<< SEARCH\n" + firstLine + "\n=======\n지운 로그\n>>>>>>> REPLACE\n```";
        assertThat(EditProposalService.apply(EditProposalService.parse(reply).get(0), before)).isPresent();
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply(reply, "test", 10, 20, 0, "stop", 1));
        attempts.chat(devId, "로그 정리해 줘", List.of(), null);
        int aiTurn = store.turns(devId).stream().filter(t -> "ai".equals(t.role())).findFirst().orElseThrow().turn();
        int index = EditProposalService.parse(reply).get(0).index();

        assertThatThrownBy(() -> attempts.decideEdit(devId, aiTurn, index, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("읽기 전용");
        assertThat(store.readFiles(devId).get("logs/app.log")).isEqualTo(before);
        assertThat(store.events(devId)).noneMatch(e -> "aiEdit".equals(e.path("type").asText()));
    }

    @Test
    void 빌드와_실행을_너무_자주_누르면_429이고_다른_응시자는_영향이_없다() throws Exception {
        when(runner.build(any())).thenReturn(new CodeRunner.BuildResult(true, List.of(), List.of(), 1));
        // 빌드와 실행을 합쳐 1분에 10번(기본값)까지
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/attempts/{id}/build", devId).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/attempts/{id}/build", devId).header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/attempts/{id}/run", devId).header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests());
        // 다른 회원은 자기 횟수가 따로다
        var other = AuthTestSupport.signup(auth, mail, "다른 응시자", UUID.randomUUID() + "@pair.test");
        String otherAttempt = attempts.startSelf(TestTasks.SELF_DEV, other.user().id()).id();
        mvc.perform(post("/api/attempts/{id}/build", otherAttempt).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk());
    }

    @Test
    void 실행_대기열이_꽉_차면_503() throws Exception {
        when(runner.build(any())).thenThrow(SandboxRunner.BusyException.class);
        when(runner.run(any())).thenThrow(SandboxRunner.BusyException.class);
        mvc.perform(post("/api/attempts/{id}/build", devId).header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/attempts/{id}/run", devId).header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
    }
}
