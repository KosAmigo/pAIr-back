package com.pair.domain.exam;

import com.pair.global.config.ExamIntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.global.security.HeaderCurrentUserResolver;

/** 콘솔 API 회사 분리: 로그인 없음 401, 역할 없음 403, 다른 회사 회차 404 */
// 테스트 속도: 컨텍스트를 같이 쓰려고 공통 설정(ExamIntegrationTest)을 상속하도록 바꿈. 설정만 옮겼고 테스트 내용은 그대로
class ConsoleAuthIntegrationTest extends ExamIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.pair.domain.task.repository.TaskRepository taskRecords;
    @Autowired com.pair.domain.task.repository.TaskFileRepository taskFiles;
    @Autowired com.pair.domain.task.repository.TaskQuestionRepository taskQuestions;

    @org.junit.jupiter.api.BeforeEach
    void seedTasks() {
        com.pair.domain.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, long user, String role, Long company) {
        b.header(HeaderCurrentUserResolver.USER_ID, user).header(HeaderCurrentUserResolver.ROLE, role);
        return company == null ? b : b.header(HeaderCurrentUserResolver.COMPANY_ID, company);
    }

    private String sessionBody(Long companyId) {
        Instant start = Instant.now().plusSeconds(3600);
        return """
                {"companyId": %s, "title": "auth", "startsAt": "%s", "endsAt": "%s", "proctoring": false, "taskIds": ["order-deadlock-live"]}
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
        mvc.perform(as(post("/api/console/sessions/" + id + "/rescue"), 6, "PROCTOR", 502L)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/console/sessions/" + id + "/rescue"), 9, "MANAGER", 777L)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/console/sessions"), 2, "USER", null)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/console/sessions/" + id), 1, "ADMIN", null)).andExpect(status().isOk());
        mvc.perform(as(post("/api/console/sessions/" + id + "/cancel"), 4, "MANAGER", 502L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("CANCELED"));
    }

    @Test
    @DisplayName("링크 복구: 매니저·감독관·관리자는 권한을 통과하고(시험 전이라 400), 응시자 역할은 403, 다른 회사는 404")
    void restoreLinkPermission() throws Exception {
        long id = createAsManager(504);
        String added = mvc.perform(as(post("/api/console/sessions/" + id + "/participants"), 4, "MANAGER", 504L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"participants\":[{\"name\":\"홍길동\",\"email\":\"restore@test.dev\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json.readTree(added).path("added").asInt()).isEqualTo(1);
        long participantId = json.readTree(mvc.perform(as(get("/api/console/sessions/" + id), 4, "MANAGER", 504L))
                .andReturn().getResponse().getContentAsString()).path("participants").get(0).path("id").asLong();
        String url = "/api/console/sessions/" + id + "/participants/" + participantId + "/restore-link";

        mvc.perform(as(post(url), 2, "USER", null)).andExpect(status().isForbidden());
        mvc.perform(as(post(url), 9, "MANAGER", 777L)).andExpect(status().isNotFound());
        // 권한은 통과하지만 아직 시작 전이라 상태 검사에서 거절된다 (진행 중인 시험에서만 되살릴 수 있다)
        for (var who : java.util.List.of(new Object[] {4L, "MANAGER", 504L}, new Object[] {6L, "PROCTOR", 504L}, new Object[] {1L, "ADMIN", null})) {
            mvc.perform(as(post(url), (Long) who[0], (String) who[1], (Long) who[2]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("진행 중인 시험")));
        }
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
