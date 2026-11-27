package com.pair.domain.taskgen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.security.HeaderCurrentUserResolver;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** GET /api/admin/task-gen/{jobId}/content: 200 모양, 없는 작업 404, 관리자만, 작업 응답의 trap·usage */
class TaskGenContentApiTest extends ExamIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskGenJobRepository jobRepo;
    @Autowired TaskGenCallRepository callRepo;

    @BeforeEach
    void clean() {
        callRepo.deleteAll();
        jobRepo.deleteAll();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role).header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    private JsonNode body(String url, int expected) throws Exception {
        var res = mvc.perform(as(get(url), "ADMIN")).andExpect(status().is(expected)).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    @Test
    @DisplayName("content: 저장된 호출을 요약해 돌려주고, 없는 단계는 null, 없는 작업은 404, 관리자가 아니면 403")
    void content() throws Exception {
        Instant now = Instant.now();
        String form = "{\"track\":\"DEV\",\"topic\":\"쿠폰\",\"trap\":\"중복 적용\",\"usage\":\"SELF\",\"minutes\":40,\"competencies\":[]}";
        jobRepo.save(new TaskGenJob("job-content-1", null, "other-env", "NOW", null, form, 1, now));
        callRepo.save(new TaskGenCall("call-1", "job-content-1", "h1", 0, "BLUEPRINT", null, "anthropic",
                "{\"title\":\"쿠폰 계산\",\"summary\":\"요약\",\"scenario\":\"상황\",\"flaws\":[{\"what\":\"결함\",\"where\":\"Foo\"}]}", 1, 1, now));
        callRepo.save(new TaskGenCall("call-2", "job-content-1", "h2", 0, "CODE", null, "anthropic",
                "{\"files\":[{\"path\":\"src/Foo.java\",\"content\":\"abcde\"}]}", 1, 1, now));

        JsonNode r = body("/api/admin/task-gen/job-content-1/content", 200);
        assertThat(r.get("jobId").asText()).isEqualTo("job-content-1");
        assertThat(r.get("status").asText()).isEqualTo("QUEUED");
        assertThat(r.get("form").get("trap").asText()).isEqualTo("중복 적용");
        assertThat(r.get("form").get("mode").asText()).isEqualTo("NOW");
        assertThat(r.get("blueprint").get("title").asText()).isEqualTo("쿠폰 계산");
        assertThat(r.get("blueprint").get("flaws").get(0).get("where").asText()).isEqualTo("Foo");
        assertThat(r.get("code").get("files").get(0).get("chars").asInt()).isEqualTo(5);
        assertThat(r.get("writing").isNull()).isTrue();
        assertThat(r.get("answer").isNull()).isTrue();
        assertThat(r.get("questions").isNull()).isTrue();
        assertThat(r.get("fillers").isNull()).isTrue();

        body("/api/admin/task-gen/없는작업/content", 404);
        mvc.perform(as(get("/api/admin/task-gen/job-content-1/content"), "USER")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("작업 응답(상태·목록)에 폼의 trap·usage 가 들어 있다")
    void statusHasTrapAndUsage() throws Exception {
        String form = "{\"track\":\"BIZ\",\"topic\":\"매출\",\"trap\":\"단위 혼동\",\"usage\":\"LIVE\",\"minutes\":20,\"competencies\":[]}";
        jobRepo.save(new TaskGenJob("job-content-2", null, "other-env", "NOW", null, form, 1, Instant.now()));
        JsonNode j = body("/api/admin/task-gen/job-content-2", 200);
        assertThat(j.get("trap").asText()).isEqualTo("단위 혼동");
        assertThat(j.get("usage").asText()).isEqualTo("LIVE");
        assertThat(j.get("topic").asText()).isEqualTo("매출");
    }
}
