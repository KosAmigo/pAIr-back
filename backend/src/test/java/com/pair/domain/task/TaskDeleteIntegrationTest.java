package com.pair.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.security.HeaderCurrentUserResolver;
import com.pair.infra.storage.TaskFileStorage;

/** 관리자 과제 삭제: 숨김 과제만 지워지고 딸린 행도 함께 사라진다. 공개·응시 기록·회차·복사본이 있으면 409 */
class TaskDeleteIntegrationTest extends ExamIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TaskRepository tasks;
    @Autowired TaskFileRepository files;
    @Autowired TaskQuestionRepository questions;
    @Autowired AttemptRepository attempts;
    @Autowired TaskGenJobRepository jobs;
    @Autowired TaskFileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.pair.domain.auth.repository.AuthAccountRepository accounts;

    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, "ADMIN");
    }

    private static MockHttpServletRequestBuilder manager(MockHttpServletRequestBuilder b) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 4).header(HeaderCurrentUserResolver.ROLE, "MANAGER")
                .header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    private Task task(long id, Task.Usage usage, Task.Status status, Long parent) {
        return tasks.save(new Task(id, "del-" + id, "삭제 시험", "DEV", 60, usage, status, parent, 1L, AT));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    @DisplayName("숨김 과제를 지우면 파일·문항·건의·페이지가 사라지고 생성 작업의 task_code 만 비며 저장소 PDF 도 지워진다")
    void deletesWithChildren() throws Exception {
        long id = 9101;
        Long userId = accounts.save(new com.pair.domain.auth.entity.AuthAccount(java.util.UUID.randomUUID() + "@pair.test", "건의자", "x")).getId();
        task(id, Task.Usage.SELF, Task.Status.HIDDEN, null);
        TaskFile pdf = new TaskFile(id, "doc.pdf", null, "application/pdf", 3, TaskFile.VISIBLE, true);
        String key = TaskFileStorage.keyOf(id, 1);
        pdf.setStorageKey(key);
        pdf = files.save(pdf);
        files.save(new TaskFile(id, "a.md", "x", "text/plain", 1, TaskFile.VISIBLE, false));
        TaskQuestion q = questions.save(new TaskQuestion(id, TaskQuestion.FOLLOWUP, "ESSAY", 1, "질문"));
        jdbc.update("insert into document_pages (task_file_id, page_no, text) values (?, 1, 't')", pdf.getId());
        jdbc.update("insert into question_sources (question_id, task_file_id, pages) values (?, ?, '1')", q.getId(), pdf.getId());
        jdbc.update("insert into question_choices (question_id, seq, content, is_correct) values (?, 1, 'c', true)", q.getId());
        jdbc.update("insert into short_answer_keys (question_id, accepted) values (?, 'a')", q.getId());
        jdbc.update("insert into task_issue_reports (task_id, user_id, category, message, status, created_at, updated_at) "
                + "values (?, ?, 'OTHER', '문제가 있어요 정말', 'NEW', now(), now())", id, userId);
        storage.put(key, new byte[] {1, 2, 3});
        String jobId = "del-job-" + id;
        jobs.save(new TaskGenJob(jobId, null, "t", TaskGenJob.NOW, null, "{}", 1L, AT));
        jdbc.update("update task_gen_jobs set status = 'DONE', task_code = 'del-9101', passed = true where id = ?", jobId);

        mvc.perform(admin(delete("/api/console/tasks/del-9101"))).andExpect(status().isNoContent());

        assertEquals(0, count("select count(*) from tasks where task_id = ?", id));
        assertEquals(0, count("select count(*) from task_files where task_id = ?", id));
        assertEquals(0, count("select count(*) from task_questions where task_id = ?", id));
        assertEquals(0, count("select count(*) from task_issue_reports where task_id = ?", id));
        assertEquals(0, count("select count(*) from document_pages where task_file_id = ?", pdf.getId()));
        assertEquals(0, count("select count(*) from question_sources where question_id = ?", q.getId()));
        assertEquals(0, count("select count(*) from question_choices where question_id = ?", q.getId()));
        assertEquals(0, count("select count(*) from short_answer_keys where question_id = ?", q.getId()));
        assertTrue(storage.get(key).isEmpty());
        TaskGenJob job = jobs.findById(jobId).orElseThrow();
        assertNull(job.getTaskCode());
        assertEquals(Boolean.TRUE, job.getPassed());
        mvc.perform(admin(delete("/api/console/tasks/del-9101"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공개 중인 과제는 409")
    void openIs409() throws Exception {
        task(9102, Task.Usage.SELF, Task.Status.OPEN, null);
        mvc.perform(admin(delete("/api/console/tasks/del-9102"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("공개 중인 과제는 지울 수 없어요. 먼저 숨기세요"));
        assertEquals(1, count("select count(*) from tasks where task_id = 9102"));
    }

    @Test
    @DisplayName("응시 기록이 있으면 409")
    void attemptIs409() throws Exception {
        task(9103, Task.Usage.SELF, Task.Status.HIDDEN, null);
        attempts.save(Attempt.self(1L, 9103L, "m", Instant.now()));
        mvc.perform(admin(delete("/api/console/tasks/del-9103"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("응시 기록")));
    }

    @Test
    @DisplayName("회차에 들어 있으면 409")
    void sessionIs409() throws Exception {
        task(9104, Task.Usage.LIVE, Task.Status.OPEN, null);
        Instant start = Instant.now().plusSeconds(3600);
        mvc.perform(manager(post("/api/console/sessions")).contentType(MediaType.APPLICATION_JSON).content("""
                {"title": "del", "startsAt": "%s", "endsAt": "%s", "proctoring": false, "taskIds": ["del-9104"]}
                """.formatted(start, start.plusSeconds(3600)))).andExpect(status().isCreated());
        mvc.perform(admin(post("/api/console/tasks/del-9104/hide"))).andExpect(status().isOk());
        mvc.perform(admin(delete("/api/console/tasks/del-9104"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("회차")));
    }

    @Test
    @DisplayName("복사본이 있는 원본은 409")
    void parentIs409() throws Exception {
        task(9105, Task.Usage.SELF, Task.Status.HIDDEN, null);
        task(9106, Task.Usage.LIVE, Task.Status.HIDDEN, 9105L);
        mvc.perform(admin(delete("/api/console/tasks/del-9105"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("복사본")));
        mvc.perform(admin(delete("/api/console/tasks/del-9106"))).andExpect(status().isNoContent());
        mvc.perform(admin(delete("/api/console/tasks/del-9105"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("관리자가 아니면 403")
    void nonAdminIs403() throws Exception {
        task(9107, Task.Usage.SELF, Task.Status.HIDDEN, null);
        mvc.perform(manager(delete("/api/console/tasks/del-9107"))).andExpect(status().isForbidden());
        assertEquals(1, count("select count(*) from tasks where task_id = 9107"));
    }
}
