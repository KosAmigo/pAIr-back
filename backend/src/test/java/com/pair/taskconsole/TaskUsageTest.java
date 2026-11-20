package com.pair.taskconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.task.TaskFileRecord;
import com.pair.task.TaskFileRecordRepository;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskRecord;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.HeaderCurrentUserResolver;

/** 검수 전 과제 용도 바꾸기와, 목록 응답의 탭·배지용 필드 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskUsageTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mail;
    @Autowired TaskRecordRepository records;
    @Autowired TaskFileRecordRepository files;
    @Autowired TaskQuestionRecordRepository questions;

    private static final Instant AT = Instant.parse("2026-11-20T00:00:00Z");

    @BeforeEach
    void seed() {
        TestTasks.seed(records, files, questions);
        files.deleteAll(files.findAll().stream().filter(f -> f.getTaskId() >= 9100).toList());
        records.deleteAll(records.findAll().stream().filter(t -> t.getId() >= 9100).toList());
        row(9100, "usage-hidden", TaskRecord.Status.HIDDEN, null);
        row(9101, "usage-open", TaskRecord.Status.OPEN, null);
        row(9102, "usage-closed", TaskRecord.Status.CLOSED, null);
        row(9103, "usage-copy", TaskRecord.Status.HIDDEN, 9100L);
        report(9100, "{\"checks\":[{\"name\":\"a\",\"ok\":true,\"detail\":\"\"},{\"name\":\"b\",\"ok\":true,\"detail\":\"\"}]}");
        report(9101, "{\"checks\":[{\"name\":\"a\",\"ok\":true,\"detail\":\"\"},{\"name\":\"b\",\"ok\":false,\"detail\":\"\"}]}");
    }

    private void row(long id, String code, TaskRecord.Status status, Long parent) {
        TaskRecord t = new TaskRecord(id, code, "제목 " + code, "DEV", 60, TaskRecord.Usage.SELF, status, parent, 1L, AT);
        t.setCard("소개 " + code, "장애 분석", "중급");
        records.save(t);
    }

    private void report(long taskId, String json) {
        files.save(new TaskFileRecord(taskId, "_gen/report.json", json, "application/json", json.length(), TaskFileRecord.HIDDEN, true));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role)
                .header(HeaderCurrentUserResolver.COMPANY_ID, 601).contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder usage(String code, String role, String usage) {
        return as(patch("/api/console/tasks/" + code + "/usage"), role).content("{\"usage\":\"" + usage + "\"}");
    }

    @Test
    @DisplayName("검수 전(HIDDEN)이면 SELF 와 LIVE 를 오갈 수 있다")
    void hiddenCanSwitch() throws Exception {
        mvc.perform(usage("usage-hidden", "ADMIN", "LIVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value("LIVE")).andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(usage("usage-hidden", "ADMIN", "SELF")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value("SELF"));
    }

    @Test
    @DisplayName("공개(OPEN)·닫힘(CLOSED)이거나 실전 복사본이면 409")
    void conflicts() throws Exception {
        mvc.perform(usage("usage-open", "ADMIN", "LIVE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("공개한 과제는 용도를 바꿀 수 없습니다. 복제해서 새로 만드세요."));
        mvc.perform(usage("usage-closed", "ADMIN", "LIVE")).andExpect(status().isConflict());
        mvc.perform(usage("usage-copy", "ADMIN", "LIVE")).andExpect(status().isConflict());
        mvc.perform(usage("no-such", "ADMIN", "LIVE")).andExpect(status().isNotFound());
        mvc.perform(usage("usage-hidden", "ADMIN", "X")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADMIN 이 아니면 403")
    void forbidden() throws Exception {
        mvc.perform(usage("usage-hidden", "MANAGER", "LIVE")).andExpect(status().isForbidden());
        mvc.perform(usage("usage-hidden", "USER", "LIVE")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("목록 응답에 track·difficulty·category·summary·createdAt·generated·checksOk 가 들어 있다")
    void listFields() throws Exception {
        String body = mvc.perform(as(get("/api/console/tasks"), "ADMIN")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, JsonNode> byId = new HashMap<>();
        new ObjectMapper().readTree(body).forEach(n -> byId.put(n.get("id").asText(), n));

        JsonNode hidden = byId.get("usage-hidden");
        assertThat(hidden.get("track").asText()).isEqualTo("DEV");
        assertThat(hidden.get("difficulty").asText()).isEqualTo("중급");
        assertThat(hidden.get("category").asText()).isEqualTo("장애 분석");
        assertThat(hidden.get("summary").asText()).isEqualTo("소개 usage-hidden");
        assertThat(hidden.get("createdAt").asText()).startsWith("2026-11-20");
        assertThat(hidden.get("generated").asBoolean()).isTrue();
        assertThat(hidden.get("checksOk").asBoolean()).isTrue();
        assertThat(byId.get("usage-open").get("checksOk").asBoolean()).isFalse();
        JsonNode plain = byId.get("usage-closed");
        assertThat(plain.get("generated").asBoolean()).isFalse();
        assertThat(plain.get("checksOk").isNull()).isTrue();
    }
}
