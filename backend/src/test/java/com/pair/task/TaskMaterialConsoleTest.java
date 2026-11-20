package com.pair.task;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pair.taskgen.TaskFileStorage;
import com.pair.web.auth.HeaderCurrentUserResolver;

/** 관리자 검수용 PDF 자료 API: ADMIN 만, 숨긴(HIDDEN) 과제도 보이고 숨은 파일은 안 나간다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class TaskMaterialConsoleTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("pair.taskgen.file-storage", () -> "local");
    }

    @Autowired MockMvc mvc;
    @Autowired TaskRecordRepository tasks;
    @Autowired TaskFileRecordRepository files;
    @Autowired DocumentPageRecordRepository pages;
    @Autowired TaskFileStorage storage;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setUp() {
        MaterialFixtures.seed(tasks, files, pages, storage);
        TaskRecord t = tasks.findByCode(TestTasks.SELF_BIZ).orElseThrow();
        t.changeStatus(TaskRecord.Status.HIDDEN, Instant.now());
        tasks.save(t);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role);
        if (role.equals("MANAGER")) b.header(HeaderCurrentUserResolver.COMPANY_ID, 601);
        return b;
    }

    @Test
    void 관리자는_숨긴_과제의_자료를_본다() throws Exception {
        mvc.perform(as(get("/api/console/tasks/pricing-plan/materials"), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[1].pages").value(25));
        mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/file").param("path", MaterialFixtures.PDF_PATH), "ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(MaterialFixtures.BYTES));
        mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/pages").param("path", MaterialFixtures.PDF_PATH).param("pages", "2-3"), "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].text").value("2쪽 글"));
    }

    @Test
    void 숨은_파일은_관리자에게도_안_나간다() throws Exception {
        mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/file").param("path", MaterialFixtures.HIDDEN_PATH), "ADMIN"))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/pages").param("path", MaterialFixtures.HIDDEN_PATH), "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 관리자_말고는_403() throws Exception {
        for (String role : new String[] {"MANAGER", "USER"}) {
            mvc.perform(as(get("/api/console/tasks/pricing-plan/materials"), role)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/file").param("path", MaterialFixtures.PDF_PATH), role))
                    .andExpect(status().isForbidden());
            mvc.perform(as(get("/api/console/tasks/pricing-plan/materials/pages").param("path", MaterialFixtures.PDF_PATH), role))
                    .andExpect(status().isForbidden());
        }
    }
}
