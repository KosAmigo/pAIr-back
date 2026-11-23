package com.pair.domain.task;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.task.repository.TaskFileRecordRepository;
import com.pair.domain.task.repository.TaskQuestionRecordRepository;
import com.pair.domain.task.entity.TaskRecord;
import com.pair.domain.task.repository.TaskRecordRepository;

/** 응시자용 과제 목록과 상세: 자가진단(SELF)이고 숨김(HIDDEN)이 아닌 과제만 보인다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskListApiTest {

    @Autowired MockMvc mvc;
    @Autowired TaskRecordRepository records;
    @Autowired TaskFileRecordRepository files;
    @Autowired TaskQuestionRecordRepository questions;

    @BeforeEach
    void setUp() {
        // 시드는 모든 과제를 OPEN 으로 되돌린다 (다른 테스트가 상태를 바꿔 두었을 수 있다)
        TestTasks.seed(records, files, questions);
    }

    @AfterEach
    void restore() {
        TestTasks.seed(records);
    }

    private void setStatus(String code, TaskRecord.Status status) {
        TaskRecord t = records.findByCode(code).orElseThrow();
        t.changeStatus(status, Instant.now());
        records.save(t);
    }

    @Test
    void 목록은_자가진단_과제만_보여_주고_실전_복사본은_뺀다() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(TestTasks.SELF_DEV))
                .andExpect(jsonPath("$[0].type").value("dev"))
                .andExpect(jsonPath("$[0].minutes").value(60))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[1].id").value(TestTasks.SELF_BIZ))
                .andExpect(content().string(not(containsString(TestTasks.LIVE_DEV))));
    }

    @Test
    void 직군으로_거를_수_있다() throws Exception {
        mvc.perform(get("/api/tasks").param("track", "biz"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(TestTasks.SELF_BIZ));
        mvc.perform(get("/api/tasks").param("track", "DEV"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(TestTasks.SELF_DEV));
    }

    @Test
    void 닫힌_과제는_목록에_보이고_숨김_과제는_뺀다() throws Exception {
        setStatus(TestTasks.SELF_BIZ, TaskRecord.Status.CLOSED);
        setStatus(TestTasks.SELF_DEV, TaskRecord.Status.HIDDEN);
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(TestTasks.SELF_BIZ))
                .andExpect(jsonPath("$[0].status").value("CLOSED"));
    }

    @Test
    void 상세는_공개_과제만_주고_숨은_파일은_담지_않는다() throws Exception {
        mvc.perform(get("/api/tasks/{id}", TestTasks.SELF_DEV))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TestTasks.SELF_DEV))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(content().string(not(containsString("HiddenOrderTest"))));
    }

    @Test
    void 숨김_과제와_실전_복사본의_상세는_없는_과제로_본다() throws Exception {
        setStatus(TestTasks.SELF_DEV, TaskRecord.Status.HIDDEN);
        mvc.perform(get("/api/tasks/{id}", TestTasks.SELF_DEV)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/{id}", TestTasks.LIVE_DEV)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/{id}", "no-such-task")).andExpect(status().isNotFound());
    }
}
