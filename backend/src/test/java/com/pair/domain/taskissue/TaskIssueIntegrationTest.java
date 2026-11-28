package com.pair.domain.taskissue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.jayway.jsonpath.JsonPath;

import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;

class TaskIssueIntegrationTest extends BearerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskRepository tasks;

    private AuthResponse member;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        member = AuthTestSupport.signup(auth, mail, "건의 회원", UUID.randomUUID() + "@example.com");
    }

    @Test
    void 회원이_건의를_저장하고_관리자가_조회해_상태를_변경한다() throws Exception {
        mvc.perform(post("/api/task-issues")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"taskId\":\"%s\",\"category\":\"QUESTION_ERROR\",\"questionNumber\":\"2번\",\"message\":\"문제의 입력 조건이 서로 맞지 않습니다.\"}").formatted(TestTasks.SELF_DEV)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        mvc.perform(get("/api/admin/task-issues").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden());

        jdbc.update("update users set role = 'ADMIN' where user_id = ?", member.user().id());
        MvcResult list = mvc.perform(get("/api/admin/task-issues").header("Authorization", "Bearer " + member.token())
                        .param("taskId", TestTasks.SELF_DEV).param("category", "QUESTION_ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].message").value("문제의 입력 조건이 서로 맞지 않습니다."))
                .andExpect(jsonPath("$.items[0].submitterEmail").exists()).andReturn();
        int issueId = JsonPath.read(list.getResponse().getContentAsString(), "$.items[0].id");

        mvc.perform(patch("/api/admin/task-issues/{issueId}/status", issueId)
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    }
}
