package com.pair.domain.attempt;

import com.pair.global.config.BearerIntegrationTest;

import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.service.AttemptService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.infra.ai.AssistantClient;
import com.pair.domain.attempt.dto.ChatTurnStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.CheckpointRepository;
import com.pair.domain.attempt.service.ChatAttachmentService.Ref;
import com.pair.domain.attempt.entity.ChatAttachment;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

import jakarta.persistence.EntityManagerFactory;

/**
 * 대시보드·복기 화면이 응시마다 부르는 리포트 조회의 비용(쿼리 수, 읽는 첨부 원문)을 고정한다.
 * 원격 DB 에서는 쿼리 한 번이 왕복 한 번이고 읽는 글자가 곧 전송량이라, 화면이 응시 수만큼 이 조회를 부르면 그대로 느려진다.
 */
// 테스트 속도: 컨텍스트를 같이 쓰려고 공통 설정(BearerIntegrationTest)을 상속하도록 바꿈. 설정만 옮겼고 테스트 내용은 그대로
class ReportQueryCostTest extends BearerIntegrationTest {

    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired AuthService auth;
    @Autowired EntityManagerFactory emf;
    @Autowired TaskRepository taskRecords;
    @Autowired TaskFileRepository taskFiles;
    @Autowired TaskQuestionRepository taskQuestions;
    @Autowired MockMvc mvc;
    @Autowired AttemptRepository records;
    @Autowired CheckpointRepository checkpoints;

    String token;
    String devId;
    String bigFile;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        var signup = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test");
        token = signup.token();
        devId = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
        bigFile = store.readFiles(devId).keySet().stream().filter(p -> p.startsWith("src/")).findFirst().orElseThrow();
        org.mockito.Mockito.when(assistant.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantClient.Reply("답", "test", 10, 20, 0, "stop", 1));
    }

    @Test
    void 리포트_조회는_첨부_원문을_읽지_않고_쿼리도_적다() throws Exception {
        // 첨부가 붙은 질문 3개 (원문이 큰 파일)
        store.writeWorkspaceFile(devId, bigFile, "// 아주 긴 파일\n" + "int x = 1;\n".repeat(2500));
        for (int i = 0; i < 3; i++) attempts.chat(devId, "질문 " + i, List.of(new Ref("ATTEMPT_FILE", bigFile, null)), null);
        store.writeReport(devId, Map.of("transcript", List.of(Map.of("turn", 1, "role", "user", "text", "질문"))));
        var t = java.time.Instant.now();
        store.save(store.load(devId).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(AttemptStateDto.Status.DONE));

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/attempts/{id}/report", devId).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.transcript[0].attachments[0].chars").isNumber());
        long attachmentRows = stats.getEntityStatistics(ChatAttachment.class.getName()).getLoadCount();
        System.out.println("[report cost] statements=" + stats.getPrepareStatementCount() + ", attachment entities loaded=" + attachmentRows);

        // 길이만 필요한데 원문(최대 3만 자 × 5개 × 질문 수)을 통째로 읽으면 안 된다
        assertThat(attachmentRows).as("리포트 조회가 읽은 첨부 원문 행 수").isZero();
    }

    @Test
    void 풀이_중_응시가_많아도_목록_쿼리는_일정하고_빈_응시는_빠진다() throws Exception {
        var signup = AuthTestSupport.signup(auth, mail, "풀이중", UUID.randomUUID() + "@pair.test");
        Long taskId = taskRecords.findByCode(TestTasks.SELF_DEV).orElseThrow().getId();
        var now = java.time.Instant.now();
        List<String> shown = new java.util.ArrayList<>();
        List<String> hidden = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String nothing = String.valueOf(records.save(Attempt.self(signup.user().id(), taskId, "m", now)).getId());
            String startOnly = String.valueOf(records.save(Attempt.self(signup.user().id(), taskId, "m", now)).getId());
            store.appendEvent(startOnly, "start", Map.of());
            String saved = String.valueOf(records.save(Attempt.self(signup.user().id(), taskId, "m", now)).getId());
            store.appendEvent(saved, "start", Map.of());
            store.appendEvent(saved, "save", Map.of());
            String asked = String.valueOf(records.save(Attempt.self(signup.user().id(), taskId, "m", now)).getId());
            store.appendTurn(asked, new ChatTurnStateDto(1, "user", now, "질문", null, null, null, null, null, null));
            hidden.addAll(List.of(nothing, startOnly));
            shown.addAll(List.of(saved, asked));
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        String body = mvc.perform(get("/api/attempts").header("Authorization", "Bearer " + signup.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long list = stats.getPrepareStatementCount();
        System.out.println("[solving cost] attempts=12, list statements=" + list);

        List<String> listed = com.jayway.jsonpath.JsonPath.read(body, "$[*].id");
        assertThat(listed).containsExactlyInAnyOrderElementsOf(shown).doesNotContainAnyElementsOf(hidden);
        assertThat(list).as("풀이 중 응시 12개의 목록 쿼리 수").isLessThanOrEqualTo(10L);
    }

    @Test
    void 복기_트리는_갈래_수와_상관없이_쿼리가_일정하다() throws Exception {
        var signup = AuthTestSupport.signup(auth, mail, "트리", UUID.randomUUID() + "@pair.test");
        String root = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
        attempts.chat(root, "질문", List.of(), null);
        Long checkpointId = checkpoints.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(root)).get(0).getId();
        Long taskId = taskRecords.findByCode(TestTasks.SELF_DEV).orElseThrow().getId();
        List<String> ids = new java.util.ArrayList<>(List.of(root));
        for (int i = 0; i < 5; i++) {
            ids.add(String.valueOf(records.save(Attempt.forkedSelf(signup.user().id(), taskId, "m", checkpointId, java.time.Instant.now())).getId()));
        }
        for (String id : ids) {
            store.writeReport(id, Map.of("version", 2, "total", 80, "max", 100,
                    "items", List.of(Map.of("key", "usage", "name", "활용", "max", 20, "score", 10))));
            var t = java.time.Instant.now();
            store.save(store.load(id).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(AttemptStateDto.Status.DONE));
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/attempts/{id}/tree", root).header("Authorization", "Bearer " + signup.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[5].score.total").value(80))
                .andExpect(jsonPath("$[5].score.items[0].name").value("활용"));
        long tree = stats.getPrepareStatementCount();
        System.out.println("[tree cost] nodes=6, statements=" + tree);
        assertThat(tree).as("갈래 6개의 트리 쿼리 수").isLessThanOrEqualTo(12L);
    }

    @Test
    void 목록은_응시_수와_상관없이_쿼리가_일정하고_리포트는_응시당_몇_번으로_제한된다() throws Exception {
        int n = 5;
        var signup = AuthTestSupport.signup(auth, mail, "대시보드", UUID.randomUUID() + "@pair.test");
        String bearer = signup.token();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
            store.writeReport(id, Map.of("version", 2, "total", 80, "items", List.of(Map.of("key", "d1", "score", 10)),
                    "transcript", List.of(Map.of("turn", 1, "role", "user", "text", "질문"))));
            var t = java.time.Instant.now();
            store.save(store.load(id).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(AttemptStateDto.Status.DONE));
            ids.add(id);
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/attempts").header("Authorization", "Bearer " + bearer)).andExpect(status().isOk());
        long list = stats.getPrepareStatementCount();
        stats.clear();
        for (String id : ids) mvc.perform(get("/api/attempts/{id}/report", id).header("Authorization", "Bearer " + bearer)).andExpect(status().isOk());
        long reports = stats.getPrepareStatementCount();
        System.out.println("[dashboard cost] attempts=" + n + ", list statements=" + list + ", report statements(" + n + " calls)=" + reports);
        // 채점이 끝난 응시의 점수는 한꺼번에 읽는다. 응시마다 리포트를 만들면 원격 DB 에서 응시 수만큼 느려진다
        assertThat(list).as("채점이 끝난 응시 " + n + "개의 목록 쿼리 수").isLessThanOrEqualTo(10L);
        assertThat(reports).as("리포트 5번 조회의 쿼리 수").isLessThanOrEqualTo(8L * n);
    }
}
