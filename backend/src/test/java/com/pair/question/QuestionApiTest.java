package com.pair.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.attempt.Attempt;
import com.pair.attempt.AttemptService;
import com.pair.attempt.AttemptStore;
import com.pair.grader.PromptVersion;
import com.pair.scoring.ScoringRecords;
import com.pair.scoring.ScoringService;
import com.pair.scoring.ScoringTestAccess;
import com.pair.scoring.TestPromptVersions;
import com.pair.scoring.db.PromptVersionRecordRepository;
import com.pair.scoring.db.ScoringRecordRepository;
import com.pair.task.QuestionChoiceRecord;
import com.pair.task.QuestionChoiceRecordRepository;
import com.pair.task.ShortAnswerKeyRecord;
import com.pair.task.ShortAnswerKeyRecordRepository;
import com.pair.task.TaskQuestionRecord;
import com.pair.task.TaskQuestionRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;
import com.pair.web.auth.AuthDtos;
import com.pair.web.auth.AuthService;
import com.pair.web.auth.AuthTestSupport;

/** 문항 API(보기·답 저장·리포트)와 채점 흐름. 실제 AI 호출은 없다 */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class QuestionApiTest {

    static final long BIZ_TASK = 2L;
    static final long DEV_TASK = 1L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptStore store;
    @Autowired TaskRecordRepository tasks;
    @Autowired TaskQuestionRecordRepository questions;
    @Autowired QuestionChoiceRecordRepository choices;
    @Autowired ShortAnswerKeyRecordRepository keys;
    @Autowired ChoiceAnswerRecordRepository choiceAnswers;
    @Autowired TextAnswerRecordRepository textAnswers;
    @Autowired QuestionResultRecordRepository results;
    @Autowired ScoringService scoring;
    @Autowired ScoringRecords scoringRecords;
    @Autowired PromptVersionRecordRepository promptVersions;
    @Autowired ScoringRecordRepository scorings;
    @MockitoBean JavaMailSender mail;

    AuthDtos.AuthResponse owner;
    AuthDtos.AuthResponse other;
    String id;
    long single, multi, shortQ, devQuestion;
    long singleA, singleB, multiA, multiB, multiC;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        results.deleteAll();
        choiceAnswers.deleteAll();
        textAnswers.deleteAll();
        for (long task : new long[] {BIZ_TASK, DEV_TASK}) {
            questions.findByTaskIdAndStageOrderBySeqAsc(task, "TASK").forEach(q -> {
                choices.deleteAll(choices.findByQuestionIdOrderByIdAsc(q.getId()));
                keys.deleteAll(keys.findByQuestionIdOrderByIdAsc(q.getId()));
                questions.delete(q);
            });
        }
        single = question(BIZ_TASK, "SINGLE", 1, 5);
        singleA = choice(single, 1, "A안", false);
        singleB = choice(single, 2, "B안", true);
        multi = question(BIZ_TASK, "MULTI", 2, 5);
        multiA = choice(multi, 1, "가", true);
        multiB = choice(multi, 2, "나", false);
        multiC = choice(multi, 3, "다", true);
        shortQ = question(BIZ_TASK, "SHORT", 3, 3);
        keys.save(new ShortAnswerKeyRecord(shortQ, "8430"));
        devQuestion = question(DEV_TASK, "SINGLE", 1, 5);
        choice(devQuestion, 1, "다른 과제 보기", true);

        owner = AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
        other = AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com");
        id = attempts.startSelf(TestTasks.SELF_BIZ, owner.user().id()).id();
    }

    private long question(long task, String type, int seq, int points) {
        TaskQuestionRecord q = new TaskQuestionRecord(task, "TASK", type, seq, type + " 문항 " + seq);
        q.setPoints(points);
        return questions.save(q).getId();
    }

    private long choice(long question, int seq, String text, boolean correct) {
        return choices.save(new QuestionChoiceRecord(question, seq, text, correct)).getId();
    }

    private String token() {
        return "Bearer " + owner.token();
    }

    private org.springframework.test.web.servlet.ResultActions putAnswer(String attemptId, long questionId, String body, String bearer) throws Exception {
        return mvc.perform(put("/api/attempts/{id}/questions/{q}/answer", attemptId, questionId)
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url, id).header("Authorization", token())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void 응시자_문항_목록에는_정답_표시와_인정_답안_값이_없다() throws Exception {
        String body = mvc.perform(get("/api/attempts/{id}/questions", id).header("Authorization", token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 관리자 검수 API 가 생겨도 응시자 응답에는 correct·answers·인정 답안(8430)이 나가면 안 된다
        assertThat(body).doesNotContain("\"correct\"").doesNotContain("\"answers\"").doesNotContain("8430");
    }

    @Test
    void 문항_목록은_순서대로_나오고_정답_정보는_없다() throws Exception {
        String body = mvc.perform(get("/api/attempts/{id}/questions", id).header("Authorization", token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode arr = json.readTree(body);
        assertThat(arr).hasSize(3);
        assertThat(arr.get(0).path("type").asText()).isEqualTo("SINGLE");
        assertThat(arr.get(0).path("choices")).hasSize(2);
        assertThat(arr.get(0).path("points").asInt()).isEqualTo(5);
        assertThat(arr.get(0).path("answer").isNull()).isTrue();
        assertThat(arr.get(2).path("type").asText()).isEqualTo("SHORT");
        assertThat(arr.get(2).path("choices")).isEmpty();
        // 정답 여부·인정 답안·근거 문서를 알 수 있는 키가 하나도 없어야 한다
        for (String bad : List.of("correct", "isCorrect", "accepted", "answerKey", "sources", "taskFile")) {
            assertThat(body).as(bad).doesNotContainIgnoringCase("\"" + bad);
        }
        assertThat(arr.get(0).path("choices").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "seq", "content");
    }

    @Test
    void 문항이_없는_과제는_빈_목록이다() throws Exception {
        String devId = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
        questions.findByTaskIdAndStageOrderBySeqAsc(DEV_TASK, "TASK").forEach(q -> {
            choices.deleteAll(choices.findByQuestionIdOrderByIdAsc(q.getId()));
            questions.delete(q);
        });
        mvc.perform(get("/api/attempts/{id}/questions", devId).header("Authorization", token())).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    }

    @Test
    void 답을_저장하면_목록에_돌아오고_다시_보내면_덮어쓴다() throws Exception {
        putAnswer(id, single, "{\"choiceIds\":[" + singleA + "]}", token()).andExpect(status().isOk());
        putAnswer(id, single, "{\"choiceIds\":[" + singleB + "]}", token()).andExpect(status().isOk());
        putAnswer(id, multi, "{\"choiceIds\":[" + multiA + "," + multiB + "]}", token()).andExpect(status().isOk());
        putAnswer(id, multi, "{\"choiceIds\":[" + multiC + "]}", token()).andExpect(status().isOk());
        putAnswer(id, shortQ, "{\"text\":\"7000\"}", token()).andExpect(status().isOk());
        putAnswer(id, shortQ, "{\"text\":\"8,430만 원\"}", token()).andExpect(status().isOk());

        JsonNode arr = getJson("/api/attempts/{id}/questions");
        assertThat(arr.get(0).path("answer").path("choiceIds")).extracting(JsonNode::asLong).containsExactly(singleB);
        assertThat(arr.get(1).path("answer").path("choiceIds")).extracting(JsonNode::asLong).containsExactly(multiC);
        assertThat(arr.get(2).path("answer").path("text").asText()).isEqualTo("8,430만 원");
        assertThat(arr.get(2).path("answer").has("choiceIds")).isFalse();
        // 작업 기록에 ANSWER 가 남는다
        assertThat(store.events(id).stream().filter(e -> "answer".equals(e.path("type").asText())).count()).isEqualTo(6);
    }

    @Test
    void 주인이_아니면_403_로그인_없으면_401() throws Exception {
        putAnswer(id, single, "{\"choiceIds\":[" + singleA + "]}", "Bearer " + other.token()).andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}/questions", id).header("Authorization", "Bearer " + other.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/attempts/{id}/questions", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void 제출_뒤에는_409() throws Exception {
        putAnswer(id, single, "{\"choiceIds\":[" + singleA + "]}", token()).andExpect(status().isOk());
        attempts.submit(id);
        putAnswer(id, single, "{\"choiceIds\":[" + singleB + "]}", token()).andExpect(status().isConflict());
    }

    @Test
    void 다른_과제_문항_보기_유형_불일치_개수_오류는_400() throws Exception {
        long devChoice = choices.findByQuestionIdOrderByIdAsc(devQuestion).get(0).getId();
        putAnswer(id, devQuestion, "{\"choiceIds\":[" + devChoice + "]}", token()).andExpect(status().isBadRequest()); // 다른 과제의 문항
        putAnswer(id, single, "{\"choiceIds\":[" + devChoice + "]}", token()).andExpect(status().isBadRequest()); // 다른 문항의 보기
        putAnswer(id, single, "{\"choiceIds\":[" + multiA + "]}", token()).andExpect(status().isBadRequest());
        putAnswer(id, single, "{\"text\":\"B안\"}", token()).andExpect(status().isBadRequest()); // 유형 불일치
        putAnswer(id, shortQ, "{\"choiceIds\":[" + singleA + "]}", token()).andExpect(status().isBadRequest());
        putAnswer(id, single, "{\"choiceIds\":[" + singleA + "," + singleB + "]}", token()).andExpect(status().isBadRequest()); // SINGLE 은 1개
        putAnswer(id, shortQ, "{\"text\":\"" + "9".repeat(201) + "\"}", token()).andExpect(status().isBadRequest()); // 200자 초과
        putAnswer(id, 999999L, "{\"text\":\"1\"}", token()).andExpect(status().isBadRequest());
        assertThat(choiceAnswers.findAll()).isEmpty();
    }

    @Test
    void 빈_답은_답_지우기로_처리하고_채점에서는_답_없음이다() throws Exception {
        putAnswer(id, single, "{\"choiceIds\":[" + singleB + "]}", token()).andExpect(status().isOk());
        putAnswer(id, multi, "{\"choiceIds\":[" + multiA + "," + multiC + "]}", token()).andExpect(status().isOk());
        putAnswer(id, shortQ, "{\"text\":\"8430\"}", token()).andExpect(status().isOk());
        assertThat(getJson("/api/attempts/{id}/questions").get(0).path("answer").isNull()).isFalse();

        for (var body : List.of(Map.entry(single, "{\"choiceIds\":[]}"), Map.entry(multi, "{\"choiceIds\":[]}"),
                Map.entry(shortQ, "{\"text\":\"  \"}"))) {
            String res = putAnswer(id, body.getKey(), body.getValue(), token()).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(res).isEqualTo("null");
        }
        JsonNode arr = getJson("/api/attempts/{id}/questions");
        for (JsonNode n : arr) assertThat(n.path("answer").isNull()).isTrue();
        assertThat(choiceAnswers.findAll()).isEmpty();
        assertThat(textAnswers.findAll()).isEmpty();
        // 빈 답도 작업 기록에 남는다 (답 3 + 지우기 3)
        assertThat(store.events(id).stream().filter(e -> "answer".equals(e.path("type").asText())).count()).isEqualTo(6);
        // 아직 답하지 않은 문항을 지워도 200
        putAnswer(id, single, "{\"choiceIds\":[]}", token()).andExpect(status().isOk());

        long sid = finishScoring(id);
        assertThat(results.findByScoringId(sid)).extracting(QuestionResultRecord::getReason).containsOnly("답 없음");
        assertThat(results.findByScoringId(sid)).extracting(QuestionResultRecord::getScore).containsOnly(0);
    }

    // ── 채점 흐름 ─────────────────

    private long finishScoring(String attemptId) {
        PromptVersion v = new PromptVersion("20261120-1100_question-test", "q", null, java.time.Instant.now(), null, "규칙", Map.of());
        TestPromptVersions.ensure(promptVersions, v);
        if (store.load(attemptId).status() == Attempt.Status.SOLVING) {
            attempts.submit(attemptId); // 제출 → 후속 질문 답 → 채점 중. 채점 모델은 부르지 않는다
            store.save(store.load(attemptId).withFollowUps(List.of("답"), List.of("질문"), java.time.Instant.now()));
        }
        long sid = scoringRecords.start(Long.parseLong(attemptId), v);
        ScoringTestAccess.saveFakeResult(scoring, sid, attemptId);
        return sid;
    }

    @Test
    void 채점하면_문항_결과가_저장되고_리포트에_문항_점수가_나온다() throws Exception {
        putAnswer(id, single, "{\"choiceIds\":[" + singleB + "]}", token()).andExpect(status().isOk()); // 정답 5점
        putAnswer(id, multi, "{\"choiceIds\":[" + multiA + "]}", token()).andExpect(status().isOk()); // 일부만 → 0점
        putAnswer(id, shortQ, "{\"text\":\"8,430 만원\"}", token()).andExpect(status().isOk()); // 정답 3점

        long sid = finishScoring(id);

        var rs = results.findByScoringId(sid);
        assertThat(rs).hasSize(3).allSatisfy(r -> assertThat(r.getMethod()).isEqualTo("AUTO"));
        assertThat(rs.stream().filter(r -> r.getQuestionId() == multi).findFirst().orElseThrow().getReason())
                .isEqualTo("오답 (답: 가 / 정답: 가, 다)");
        assertThat(store.load(id).status()).isEqualTo(Attempt.Status.DONE);

        JsonNode body = getJson("/api/attempts/{id}/report");
        JsonNode q = body.path("questions");
        assertThat(q.path("score").asInt()).isEqualTo(8);
        assertThat(q.path("max").asInt()).isEqualTo(13);
        assertThat(q.path("items")).hasSize(3);
        JsonNode second = q.path("items").get(1);
        assertThat(second.path("seq").asInt()).isEqualTo(2);
        assertThat(second.path("score").asInt()).isZero();
        assertThat(second.path("correct").asBoolean()).isFalse();
        assertThat(second.path("yourAnswer").asText()).isEqualTo("가");
        assertThat(second.path("correctAnswer").asText()).isEqualTo("가, 다");
        assertThat(q.path("items").get(2).path("correctAnswer").asText()).isEqualTo("8430");
        // 6개 역량 점수(총점)는 문항 점수와 따로다
        assertThat(body.path("report").path("max").asInt()).isEqualTo(100);
    }

    @Test
    void 답을_안_하면_답_없음_0점이고_다시_채점하면_새_채점에_저장된다() throws Exception {
        long first = finishScoring(id);
        assertThat(results.findByScoringId(first)).extracting(QuestionResultRecord::getReason).containsOnly("답 없음");
        putAnswer(id, single, "{\"choiceIds\":[" + singleB + "]}", token()).andExpect(status().isConflict()); // 채점이 끝난 응시는 답을 못 바꾼다

        long second = finishScoring(id);
        assertThat(second).isNotEqualTo(first);
        assertThat(results.findByScoringId(second)).hasSize(3);
        assertThat(results.findByScoringId(first)).hasSize(3); // 이전 채점 결과는 그대로
        assertThat(getJson("/api/attempts/{id}/report").path("questions").path("score").asInt()).isZero();
    }

    @Test
    void 문항이_없는_과제의_리포트는_questions_가_null() throws Exception {
        questions.findByTaskIdAndStageOrderBySeqAsc(BIZ_TASK, "TASK").forEach(q -> {
            choices.deleteAll(choices.findByQuestionIdOrderByIdAsc(q.getId()));
            keys.deleteAll(keys.findByQuestionIdOrderByIdAsc(q.getId()));
            questions.delete(q);
        });
        finishScoring(id);
        JsonNode body = getJson("/api/attempts/{id}/report");
        assertThat(body.has("questions")).isTrue();
        assertThat(body.path("questions").isNull()).isTrue();
    }
}
