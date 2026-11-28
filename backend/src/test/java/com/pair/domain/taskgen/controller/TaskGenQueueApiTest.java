package com.pair.domain.taskgen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.security.HeaderCurrentUserResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 대기열 API: 시작(202), 자동 생성(topics 그대로), 목록(active/batchId), 상태(queuePosition), 취소(409), 권한, 입력 검사.
 * 공유 컨텍스트는 워커가 꺼져 있어 작업은 대기열에 그대로 남는다. 주제 미리보기는 AI 가 필요해 TaskAutoServiceTest 가 확인한다
 */
class TaskGenQueueApiTest extends ExamIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskGenJobRepository jobRepo;

    @BeforeEach
    void clean() {
        jobRepo.deleteAll();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header(HeaderCurrentUserResolver.USER_ID, 1).header(HeaderCurrentUserResolver.ROLE, role).header(HeaderCurrentUserResolver.COMPANY_ID, 601);
    }

    private JsonNode body(MockHttpServletRequestBuilder b, int expected) throws Exception {
        var res = mvc.perform(as(b, "ADMIN")).andExpect(status().is(expected)).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.readTree(text);
    }

    private static final String TOPICS = """
            {"track":"DEV","minutes":40,"usage":"SELF","mode":"BATCH","batchDeadlineHours":6,
             "topics":[{"situation":"환불 기간","trap":"당일 포함 여부를 놓침","trapType":"R1 경계 포함 여부","competencies":["control"]},
                       {"situation":"정산 마감","trap":"마감 시각을 놓침","trapType":"R2 업무 기준 시간대·마감","competencies":["usage"]}]}
            """;

    @Test
    @DisplayName("POST /task-gen: 202 {jobId}, 대기 상태로 저장되고 mode·deadlineAt·queuePosition·topic·track·minutes 가 응답에 들어 있다")
    void startAndStatus() throws Exception {
        String form = "{\"track\":\"DEV\",\"topic\":\"쿠폰 할인 계산\",\"trap\":\"쿠폰 중복 적용\",\"mode\":\"BATCH\",\"batchDeadlineHours\":12}";
        String id = body(post("/api/admin/task-gen").contentType(MediaType.APPLICATION_JSON).content(form), 202).get("jobId").asText();
        String id2 = body(post("/api/admin/task-gen").contentType(MediaType.APPLICATION_JSON)
                .content("{\"track\":\"DEV\",\"topic\":\"둘째\",\"trap\":\"함정\"}"), 202).get("jobId").asText();

        JsonNode j = body(get("/api/admin/task-gen/" + id), 200);
        assertThat(j.get("status").asText()).isEqualTo("QUEUED");
        assertThat(j.get("mode").asText()).isEqualTo("BATCH");
        assertThat(j.get("deadlineAt").isNull()).isFalse();
        assertThat(j.get("queuePosition").asInt()).isEqualTo(1);
        assertThat(j.get("topic").asText()).isEqualTo("쿠폰 할인 계산");
        assertThat(j.get("track").asText()).isEqualTo("DEV");
        assertThat(j.get("minutes").asInt()).isEqualTo(60);
        assertThat(j.has("taskCode") && j.has("checks") && j.has("error") && j.has("step")).isTrue(); // 프런트가 쓰는 기존 칸
        JsonNode j2 = body(get("/api/admin/task-gen/" + id2), 200);
        assertThat(j2.get("mode").asText()).isEqualTo("NOW");
        assertThat(j2.get("queuePosition").asInt()).isEqualTo(2);

        body(post("/api/admin/task-gen").contentType(MediaType.APPLICATION_JSON)
                .content("{\"track\":\"DEV\",\"topic\":\"x\",\"trap\":\"y\",\"mode\":\"BATCH\",\"batchDeadlineHours\":2}"), 400);
        body(post("/api/admin/task-gen").contentType(MediaType.APPLICATION_JSON)
                .content("{\"track\":\"DEV\",\"topic\":\"x\",\"trap\":\"y\",\"mode\":\"LATER\"}"), 400);
    }

    @Test
    @DisplayName("POST /task-gen/auto: topics 를 주면 그대로 주제마다 작업 하나(QUEUED), 202 {batchId, jobIds}. jobs?batchId= 로 묶음을 본다")
    void autoWithTopics() throws Exception {
        JsonNode r = body(post("/api/admin/task-gen/auto").contentType(MediaType.APPLICATION_JSON).content(TOPICS), 202);
        assertThat(r.get("jobIds")).hasSize(2);
        String batchId = r.get("batchId").asText();

        JsonNode list = body(get("/api/admin/task-gen/jobs").param("batchId", batchId), 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("topic").asText()).isEqualTo("환불 기간");
        assertThat(list.get(0).get("status").asText()).isEqualTo("QUEUED");
        assertThat(list.get(0).get("mode").asText()).isEqualTo("BATCH");
        assertThat(list.get(0).get("batchId").asText()).isEqualTo(batchId);
        assertThat(list.get(0).get("minutes").asInt()).isEqualTo(40);
        assertThat(list.get(1).get("queuePosition").asInt()).isEqualTo(2);
        String saved = jobRepo.findById(list.get(0).get("jobId").asText()).orElseThrow().getForm();
        assertThat(saved).contains("[R1 경계 포함 여부] 당일 포함 여부를 놓침");

        assertThat(body(get("/api/admin/task-gen/jobs").param("active", "true"), 200)).hasSize(2);
        assertThat(body(get("/api/admin/task-gen/jobs").param("batchId", "no-such"), 200)).isEmpty();
    }

    @Test
    @DisplayName("POST /{jobId}/cancel: 대기 작업은 CANCELED, 이미 취소·종료됐으면 409, 없으면 404")
    void cancel() throws Exception {
        JsonNode r = body(post("/api/admin/task-gen/auto").contentType(MediaType.APPLICATION_JSON).content(TOPICS), 202);
        String first = r.get("jobIds").get(0).asText();

        assertThat(body(post("/api/admin/task-gen/" + first + "/cancel"), 200).get("status").asText()).isEqualTo("CANCELED");
        body(post("/api/admin/task-gen/" + first + "/cancel"), 409);
        body(post("/api/admin/task-gen/none/cancel"), 404);
        // 취소하면 뒤 작업의 순번이 당겨진다
        assertThat(body(get("/api/admin/task-gen/" + r.get("jobIds").get(1).asText()), 200).get("queuePosition").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("확인(seen): 끝난 작업은 확인하면 목록에서 빠지고(멱등), 단일 조회는 그대로. 안 끝난 작업은 409, 없으면 404. 모두 확인은 끝난 것만")
    void seen() throws Exception {
        String auto3 = """
                {"track":"DEV","minutes":40,"usage":"SELF","mode":"NOW",
                 "topics":[{"situation":"환불 기간","trap":"당일 포함","trapType":"R1 경계 포함 여부","competencies":["control"]},
                           {"situation":"정산 마감","trap":"마감 시각","trapType":"R2 업무 기준 시간대·마감","competencies":["usage"]},
                           {"situation":"포인트 적립","trap":"반올림","trapType":"R3 회사 반올림·절사 규칙","competencies":["usage"]}]}
                """;
        JsonNode r = body(post("/api/admin/task-gen/auto").contentType(MediaType.APPLICATION_JSON).content(auto3), 202);
        String a = r.get("jobIds").get(0).asText(), b = r.get("jobIds").get(1).asText(), c = r.get("jobIds").get(2).asText();
        body(post("/api/admin/task-gen/" + a + "/cancel"), 200);
        body(post("/api/admin/task-gen/" + b + "/cancel"), 200);

        // 끝났는데 확인 안 한 작업은 목록에 남고, finishedAt 이 있고 seenAt 은 비어 있다
        JsonNode list = body(get("/api/admin/task-gen/jobs"), 200);
        assertThat(list).hasSize(3);
        JsonNode endedA = body(get("/api/admin/task-gen/" + a), 200);
        assertThat(endedA.get("finishedAt").isNull()).isFalse();
        assertThat(endedA.get("seenAt").isNull()).isTrue();

        JsonNode seenA = body(post("/api/admin/task-gen/" + a + "/seen"), 200);
        assertThat(seenA.get("seenAt").isNull()).isFalse();
        String firstSeenAt = seenA.get("seenAt").asText();
        assertThat(body(post("/api/admin/task-gen/" + a + "/seen"), 200).get("seenAt").asText()).isEqualTo(firstSeenAt); // 멱등: 처음 시각 그대로
        assertThat(body(get("/api/admin/task-gen/jobs"), 200).findValuesAsText("jobId")).containsExactlyInAnyOrder(b, c);
        assertThat(body(get("/api/admin/task-gen/" + a), 200).get("status").asText()).isEqualTo("CANCELED"); // 주소로 직접 들어오면 그대로 보인다

        body(post("/api/admin/task-gen/" + c + "/seen"), 409); // 아직 대기 중
        body(post("/api/admin/task-gen/none/seen"), 404);

        // 모두 확인: 끝난 b 만 바뀌고 대기 중 c 는 남는다
        assertThat(body(post("/api/admin/task-gen/seen-all"), 200).get("seen").asInt()).isEqualTo(1);
        assertThat(body(get("/api/admin/task-gen/jobs"), 200).findValuesAsText("jobId")).containsExactly(c);
        assertThat(body(post("/api/admin/task-gen/seen-all"), 200).get("seen").asInt()).isZero();
        // 묶음 조회(batchId)는 확인 여부와 상관없이 전부
        assertThat(body(get("/api/admin/task-gen/jobs").param("batchId", r.get("batchId").asText()), 200)).hasSize(3);
    }

    @Test
    @DisplayName("관리자만: 일반 사용자는 모든 새 API 가 403, 입력이 잘못되면 400")
    void authAndValidation() throws Exception {
        for (var b : new MockHttpServletRequestBuilder[] {
                post("/api/admin/task-gen/auto").contentType(MediaType.APPLICATION_JSON).content(TOPICS),
                post("/api/admin/task-gen/auto/preview").contentType(MediaType.APPLICATION_JSON).content("{\"track\":\"DEV\",\"count\":3}"),
                get("/api/admin/task-gen/jobs").param("active", "true"),
                post("/api/admin/task-gen/x/cancel"),
                post("/api/admin/task-gen/x/seen"),
                post("/api/admin/task-gen/seen-all")}) {
            mvc.perform(as(b, "USER")).andExpect(status().isForbidden());
        }
        body(post("/api/admin/task-gen/auto/preview").contentType(MediaType.APPLICATION_JSON).content("{\"track\":\"DEV\",\"count\":99}"), 400);
        body(post("/api/admin/task-gen/auto/preview").contentType(MediaType.APPLICATION_JSON).content("{\"track\":\"NOPE\",\"count\":3}"), 400);
        body(post("/api/admin/task-gen/auto").contentType(MediaType.APPLICATION_JSON)
                .content("{\"track\":\"DEV\",\"topics\":[{\"situation\":\" \",\"trap\":\"t\"}]}"), 400);
        body(get("/api/admin/task-gen/jobs").param("active", "false"), 400);
        mvc.perform(as(get("/api/admin/task-gen/jobs"), "ADMIN")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
