package com.pair.exam;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.web.auth.HeaderCurrentUserResolver;

/** 콘솔 API 회사 분리: 로그인 없음 401, 역할 없음 403, 다른 회사 회차 404 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class ConsoleAuthIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JavaMailSender mail;
    @Autowired com.pair.taskconsole.TaskConsoleService taskConsole;

    @org.junit.jupiter.api.BeforeEach
    void publishTasks() {
        taskConsole.changeStatus("order-deadlock", com.pair.taskconsole.TaskPublication.Status.OPEN, 1L);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, long user, String role, Long company) {
        b.header(HeaderCurrentUserResolver.USER_ID, user).header(HeaderCurrentUserResolver.ROLE, role);
        return company == null ? b : b.header(HeaderCurrentUserResolver.COMPANY_ID, company);
    }

    private String sessionBody(Long companyId) {
        Instant start = Instant.now().plusSeconds(3600);
        return """
                {"companyId": %s, "title": "auth", "startsAt": "%s", "endsAt": "%s", "proctoring": false, "taskIds": ["order-deadlock"]}
                """.formatted(companyId, start, start.plusSeconds(3600));
    }

    private long createAsManager(long company) throws Exception {
        String res = mvc.perform(as(post("/api/console/sessions"), 4, "MANAGER", company)
                        .contentType(MediaType.APPLICATION_JSON).content(sessionBody(999L)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("id").asLong();
    }

    @Test
    @DisplayName("로그인 정보가 없으면 401, 틀린 형식이면 401")
    void unauthorized() throws Exception {
        mvc.perform(get("/api/console/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/console/sessions").header(HeaderCurrentUserResolver.USER_ID, "x")
                .header(HeaderCurrentUserResolver.ROLE, "MANAGER")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("매니저가 만든 회차는 요청의 companyId 가 아니라 로그인한 회사로 저장되고, 목록도 자기 회사만")
    void managerCompanyComesFromLogin() throws Exception {
        long id = createAsManager(501);

        String list = mvc.perform(as(get("/api/console/sessions"), 4, "MANAGER", 501L))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode arr = json.readTree(list);
        org.assertj.core.api.Assertions.assertThat(arr.findValuesAsText("id")).contains(String.valueOf(id));
        String other = mvc.perform(as(get("/api/console/sessions"), 9, "MANAGER", 999L))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json.readTree(other).findValuesAsText("id")).doesNotContain(String.valueOf(id));
    }

    @Test
    @DisplayName("다른 회사 회차는 404, 감독관은 보기만 되고 관리·결과는 403, 응시자 역할은 403")
    void companyAndRoleSeparation() throws Exception {
        long id = createAsManager(502);

        mvc.perform(as(get("/api/console/sessions/" + id), 9, "MANAGER", 777L)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/console/sessions/" + id), 6, "PROCTOR", 502L)).andExpect(status().isOk());
        mvc.perform(as(post("/api/console/sessions/" + id + "/cancel"), 6, "PROCTOR", 502L)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/console/sessions/" + id + "/results"), 6, "PROCTOR", 502L)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/console/sessions"), 2, "USER", null)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/console/sessions/" + id), 1, "ADMIN", null)).andExpect(status().isOk());
        mvc.perform(as(post("/api/console/sessions/" + id + "/cancel"), 4, "MANAGER", 502L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("CANCELED"));
    }

    @Test
    @DisplayName("관리자는 회차를 만들 때 회사를 넣어야 한다")
    void adminMustChooseCompany() throws Exception {
        mvc.perform(as(post("/api/console/sessions"), 1, "ADMIN", null).contentType(MediaType.APPLICATION_JSON).content(sessionBody(null)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/console/sessions"), 1, "ADMIN", null).contentType(MediaType.APPLICATION_JSON).content(sessionBody(503L)))
                .andExpect(status().isCreated());
    }
}
