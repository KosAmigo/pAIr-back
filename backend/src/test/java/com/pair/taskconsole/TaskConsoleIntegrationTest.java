package com.pair.taskconsole;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.web.auth.HeaderCurrentUserResolver;

/** 과제 공개 상태: 관리자만 공개·숨김, 매니저는 공개 과제만 보고, 숨긴 과제는 회차에 못 건다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskConsoleIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    @Autowired com.pair.task.TaskRecordRepository taskRecords;
    @Autowired com.pair.task.TaskFileRecordRepository taskFiles;
    @Autowired com.pair.task.TaskQuestionRecordRepository taskQuestions;

    @org.junit.jupiter.api.BeforeEach
    void seedTasks() {
        com.pair.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, "ADMIN");
    }

    private static MockHttpServletRequestBuilder manager(MockHttpServletRequestBuilder b) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 4).header(HeaderCurrentUserResolver.ROLE, "MANAGER")
                .header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    private static String session(String taskId) {
        Instant start = Instant.now().plusSeconds(3600);
        return """
                {"title": "pub", "startsAt": "%s", "endsAt": "%s", "proctoring": false, "taskIds": ["%s"]}
                """.formatted(start, start.plusSeconds(3600), taskId);
    }

    @Test
    @DisplayName("숨긴 과제는 매니저 목록에서 빠지고 회차에 못 건다. 공개하면 걸 수 있고 공개 시각이 남는다")
    void publishFlow() throws Exception {
        mvc.perform(admin(post("/api/console/tasks/pricing-plan-live/hide")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(manager(get("/api/console/tasks"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem("pricing-plan-live"))));
        mvc.perform(manager(post("/api/console/sessions")).contentType(MediaType.APPLICATION_JSON).content(session("pricing-plan-live")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("공개")));

        mvc.perform(admin(post("/api/console/tasks/pricing-plan-live/publish")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
        mvc.perform(manager(get("/api/console/tasks"))).andExpect(jsonPath("$[*].id", hasItem("pricing-plan-live")));
        mvc.perform(manager(post("/api/console/sessions")).contentType(MediaType.APPLICATION_JSON).content(session("pricing-plan-live")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("공개·숨김·상세는 관리자만, 없는 과제는 404")
    void adminOnly() throws Exception {
        mvc.perform(manager(post("/api/console/tasks/order-deadlock/publish"))).andExpect(status().isForbidden());
        mvc.perform(manager(get("/api/console/tasks/order-deadlock"))).andExpect(status().isForbidden());
        mvc.perform(admin(get("/api/console/tasks/order-deadlock")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.definition.id").value("order-deadlock"));
        mvc.perform(admin(post("/api/console/tasks/no-such/publish"))).andExpect(status().isNotFound());
    }
}
