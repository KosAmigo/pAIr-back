package com.pair.domain.attempt;

import com.pair.domain.attempt.repository.AttemptStore;
import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.attempt.service.AttemptService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.infra.ai.AssistantClient;
import com.pair.domain.attempt.service.ChatAttachmentService.Ref;
import com.pair.domain.attempt.entity.ChatAttachmentRecord;
import com.pair.domain.task.repository.TaskFileRecordRepository;
import com.pair.domain.task.repository.TaskQuestionRecordRepository;
import com.pair.domain.task.repository.TaskRecordRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

import jakarta.persistence.EntityManagerFactory;

/**
 * 대시보드·복기 화면이 응시마다 부르는 리포트 조회의 비용(쿼리 수, 읽는 첨부 원문)을 고정한다.
 * 원격 DB 에서는 쿼리 한 번이 왕복 한 번이고 읽는 글자가 곧 전송량이라, 화면이 응시 수만큼 이 조회를 부르면 그대로 느려진다.
 */
@SpringBootTest(properties = {"pair.auth.mode=bearer", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class ReportQueryCostTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired AttemptService attempts;
    @Autowired AttemptStore store;
    @Autowired AuthService auth;
    @Autowired EntityManagerFactory emf;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    @MockitoBean AssistantClient assistant;

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
        store.save(store.load(devId).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(Attempt.Status.DONE));

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/attempts/{id}/report", devId).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.transcript[0].attachments[0].chars").isNumber());
        long attachmentRows = stats.getEntityStatistics(ChatAttachmentRecord.class.getName()).getLoadCount();
        System.out.println("[report cost] statements=" + stats.getPrepareStatementCount() + ", attachment entities loaded=" + attachmentRows);

        // 길이만 필요한데 원문(최대 3만 자 × 5개 × 질문 수)을 통째로 읽으면 안 된다
        assertThat(attachmentRows).as("리포트 조회가 읽은 첨부 원문 행 수").isZero();
    }

    @Test
    void 대시보드가_부르는_목록과_리포트는_응시_수에_비례해_쿼리가_늘어도_응시당_몇_번으로_제한된다() throws Exception {
        int n = 5;
        var signup = AuthTestSupport.signup(auth, mail, "대시보드", UUID.randomUUID() + "@pair.test");
        String bearer = signup.token();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = attempts.startSelf(TestTasks.SELF_DEV, signup.user().id()).id();
            store.writeReport(id, Map.of("version", 2, "total", 80, "items", List.of(Map.of("key", "d1", "score", 10)),
                    "transcript", List.of(Map.of("turn", 1, "role", "user", "text", "질문"))));
            var t = java.time.Instant.now();
            store.save(store.load(id).withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(Attempt.Status.DONE));
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
        // 같은 과제의 정의를 응시마다 다시 읽던 때는 목록만 응시당 6~7번(5개에 33번)이었다. 응시당 4번 이내로 고정한다
        assertThat(list).as("목록 쿼리 수").isLessThanOrEqualTo(4L * n + 3);
        assertThat(reports).as("리포트 5번 조회의 쿼리 수").isLessThanOrEqualTo(8L * n);
    }
}
