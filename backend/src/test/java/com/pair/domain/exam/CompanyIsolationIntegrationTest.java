package com.pair.domain.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.security.HeaderCurrentUserResolver;

/**
 * 회사 분리: 다른 회사 매니저는 회차 아래 어떤 API 로도 남의 회차를 보거나 바꿀 수 없다.
 * (1) 남의 회차 ID 를 직접 넣으면 404. (2) 내 회차 ID 에 남의 회차 대상자 ID 를 섞어 넣어도 성공하지 않는다.
 */
class CompanyIsolationIntegrationTest extends ExamIntegrationTest {

    private static final long OWNER_COMPANY = 611, OTHER_COMPANY = 612;
    private static final long OWNER = 41, OTHER = 42;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.pair.domain.task.repository.TaskRepository taskRecords;
    @Autowired com.pair.domain.task.repository.TaskFileRepository taskFiles;
    @Autowired com.pair.domain.task.repository.TaskQuestionRepository taskQuestions;

    long ownerSession, ownerParticipant, otherSession;

    @BeforeEach
    void setUp() throws Exception {
        com.pair.domain.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        ownerSession = createSession(OWNER, OWNER_COMPANY);
        ownerParticipant = addParticipant(OWNER, OWNER_COMPANY, ownerSession, "owner-" + ownerSession + "@test.dev");
        otherSession = createSession(OTHER, OTHER_COMPANY);
        addParticipant(OTHER, OTHER_COMPANY, otherSession, "other-" + otherSession + "@test.dev");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, long user, long company) {
        return b.header(HeaderCurrentUserResolver.USER_ID, user).header(HeaderCurrentUserResolver.ROLE, "MANAGER")
                .header(HeaderCurrentUserResolver.COMPANY_ID, company);
    }

    private static String sessionBody() {
        Instant start = Instant.now().plusSeconds(3600);
        return """
                {"title": "isolation", "startsAt": "%s", "endsAt": "%s", "proctoring": true, "taskIds": ["order-deadlock-live"]}
                """.formatted(start, start.plusSeconds(3600));
    }

    private long createSession(long user, long company) throws Exception {
        String res = mvc.perform(as(post("/api/console/sessions"), user, company).contentType(MediaType.APPLICATION_JSON).content(sessionBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("id").asLong();
    }

    private long addParticipant(long user, long company, long session, String email) throws Exception {
        mvc.perform(as(post("/api/console/sessions/" + session + "/participants"), user, company).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participants\":[{\"name\":\"대상자\",\"email\":\"" + email + "\"}]}"))
                .andExpect(status().isOk());
        String detail = mvc.perform(as(get("/api/console/sessions/" + session), user, company))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(detail).path("participants").get(0).path("id").asLong();
    }

    /** 회차 아래 API 전부. s = 회차 ID, p = 대상자 ID */
    private static List<MockHttpServletRequestBuilder> sessionScoped(long s, long p) {
        String base = "/api/console/sessions/" + s;
        String notice = "{\"message\":\"안내\"}";
        String review = "{\"review\":\"SUSPECT\",\"note\":\"x\"}";
        return List.of(
                get(base), put(base).contentType(MediaType.APPLICATION_JSON).content(sessionBody()),
                post(base + "/cancel"),
                post(base + "/participants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participants\":[{\"name\":\"침입\",\"email\":\"intruder@test.dev\"}]}"),
                post(base + "/participants/" + p + "/invite"), post(base + "/participants/" + p + "/restore-link"),
                get(base + "/events"), get(base + "/participants/" + p + "/events"),
                put(base + "/events/1/review").contentType(MediaType.APPLICATION_JSON).content(review),
                get(base + "/events/1/snapshot"), get(base + "/events/1/clip"), get(base + "/clips"),
                get(base + "/retention"), post(base + "/retention/extend"), post(base + "/rescue"),
                get(base + "/participants/" + p + "/env-check"), get(base + "/env-check-failures"),
                post(base + "/participants/" + p + "/notices").contentType(MediaType.APPLICATION_JSON).content(notice),
                get(base + "/participants/" + p + "/notices"),
                get(base + "/results"), get(base + "/results.csv"), get(base + "/results.xlsx"));
    }

    @Test
    void otherCompanyGetsNotFoundOnEverySessionEndpoint() throws Exception {
        List<String> leaked = new ArrayList<>();
        for (MockHttpServletRequestBuilder req : sessionScoped(ownerSession, ownerParticipant)) {
            var res = mvc.perform(as(req, OTHER, OTHER_COMPANY)).andReturn();
            int code = res.getResponse().getStatus();
            if (code != 404) leaked.add(res.getRequest().getMethod() + " " + res.getRequest().getRequestURI() + " → " + code);
        }
        assertThat(leaked).as("다른 회사 매니저가 404 가 아닌 응답을 받은 API").isEmpty();
        // 아무것도 바뀌지 않았다: 내 회차는 그대로 예정 상태이고 대상자도 1명
        var detail = json.readTree(mvc.perform(as(get("/api/console/sessions/" + ownerSession), OWNER, OWNER_COMPANY))
                .andReturn().getResponse().getContentAsString());
        assertThat(detail.path("participants")).hasSize(1);
        assertThat(detail.path("phase").asText()).isNotEqualTo("CANCELED");
    }

    @Test
    void ownSessionWithForeignParticipantDoesNotSucceed() throws Exception {
        // 다른 회사 매니저가 자기 회차 ID 에 남의 대상자 ID 를 섞어 넣는다
        String base = "/api/console/sessions/" + otherSession + "/participants/" + ownerParticipant;
        List<MockHttpServletRequestBuilder> mixed = List.of(
                post(base + "/invite"), post(base + "/restore-link"), get(base + "/events"), get(base + "/env-check"),
                post(base + "/notices").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"안내\"}"),
                get(base + "/notices"));
        List<String> leaked = new ArrayList<>();
        for (MockHttpServletRequestBuilder req : mixed) {
            var res = mvc.perform(as(req, OTHER, OTHER_COMPANY)).andReturn();
            int code = res.getResponse().getStatus();
            String body = res.getResponse().getContentAsString();
            // 빈 목록이나 "없음"은 새는 것이 아니다. 남의 대상자에 대한 데이터를 돌려주거나 바꾸면 실패
            boolean emptyRead = code == 200 && ("[]".equals(body.trim()) || body.isBlank());
            if (code >= 200 && code < 300 && !emptyRead) {
                leaked.add(res.getRequest().getMethod() + " " + res.getRequest().getRequestURI() + " → " + code + " " + body);
            }
        }
        assertThat(leaked).as("남의 대상자 ID 로 성공한 API").isEmpty();
    }
}
