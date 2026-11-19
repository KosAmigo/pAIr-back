package com.pair.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;

/** 응시 기록이 DB 에 저장되고 그대로 다시 읽히는지 (H2 메모리 DB) */
@SpringBootTest
@ActiveProfiles("examtest")
class DbAttemptStoreTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired AttemptStore store;
    @Autowired AttemptRegistry registry;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired ObjectMapper json;

    String id;
    Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords);
        AttemptRecord r = registry.save(AttemptRecord.self(1L, 1L, "gpt-test", start));
        id = String.valueOf(r.getId());
        store.create(new Attempt(id, TestTasks.SELF_DEV, "openai", "gpt-test", start, null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null), Map.of("src/A.java", "class A {}", "README.md", "# 읽기"));
    }

    @Test
    void 실제_응시는_DB_저장소를_쓴다() {
        assertThat(store).isInstanceOf(DbAttemptStore.class);
    }

    @Test
    void 응시_상태를_저장하고_읽는다() {
        Attempt a = store.load(id);
        assertThat(a.taskId()).isEqualTo(TestTasks.SELF_DEV);
        assertThat(a.provider()).isEqualTo("openai");
        assertThat(a.status()).isEqualTo(Attempt.Status.SOLVING);
        assertThat(a.followUpAnswers()).isEmpty();
        assertThat(a.followUpQuestions()).isNull();

        Instant t = start.plusSeconds(60);
        store.save(a.withFirstValid(t).withSubmitted(t.plusSeconds(1)));
        store.save(store.load(id).withFollowUps(List.of("답1", "답2"), List.of("질문1", "질문2"), t.plusSeconds(2)));

        Attempt b = store.load(id);
        assertThat(b.status()).isEqualTo(Attempt.Status.SCORING);
        assertThat(b.firstValidAt()).isEqualTo(t);
        assertThat(b.followUpAnswers()).containsExactly("답1", "답2");
        assertThat(b.followUpQuestions()).containsExactly("질문1", "질문2");
        assertThat(store.ids()).contains(id);

        store.save(b.withError("API 키 없음"));
        assertThat(store.load(id).error()).isEqualTo("API 키 없음");
        assertThat(store.load(id).status()).isEqualTo(Attempt.Status.ERROR);
    }

    @Test
    void 작업_파일은_있는_것만_고치고_새_파일은_규칙대로_만든다() {
        store.writeWorkspaceFile(id, "src/A.java", "class A { int x; }");
        assertThat(store.readFiles(id)).containsEntry("src/A.java", "class A { int x; }").containsKey("README.md");

        assertThatThrownBy(() -> store.writeWorkspaceFile(id, "src/없음.java", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createWorkspaceFile(id, "logs/a.java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createWorkspaceFile(id, "../a.java")).isInstanceOf(IllegalArgumentException.class);

        store.createWorkspaceFile(id, "src/B.java");
        store.createWorkspaceFile(id, "src/B.java"); // 두 번 불러도 안전
        assertThat(store.readFiles(id)).containsEntry("src/B.java", "");
    }

    @Test
    void 대화_턴을_요청_원문까지_저장한다() {
        Instant at = start.plusSeconds(5);
        var req = new ChatTurn.AssistantRequest("gpt-test", 1000, "low", "시스템", List.of(new ChatTurn.Message("user", "안녕")));
        store.appendTurn(id, new ChatTurn(1, "user", at, "안녕", null, null, null, null, null, null));
        store.appendTurn(id, new ChatTurn(2, "ai", at, "반가워요", "openai", "gpt-test", req, 10L, 20L, 300L));

        List<ChatTurn> turns = store.turns(id);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).role()).isEqualTo("user");
        ChatTurn ai = turns.get(1);
        assertThat(ai.role()).isEqualTo("ai");
        assertThat(ai.text()).isEqualTo("반가워요");
        assertThat(ai.request()).isEqualTo(req);
        assertThat(ai.inputTokens()).isEqualTo(10L);
        assertThat(ai.latencyMs()).isEqualTo(300L);
    }

    @Test
    void 이벤트는_기록_순서와_이름을_그대로_돌려준다() {
        store.appendEvent(id, "start", Map.of("taskId", TestTasks.SELF_DEV));
        store.appendEvent(id, "aiEdit", Map.of("turn", 2, "index", 0, "action", "apply"));
        store.appendEvent(id, "chatEmpty", Map.of());

        List<JsonNode> events = store.events(id);
        assertThat(events).extracting(e -> e.path("type").asText()).containsExactly("start", "aiEdit", "chatEmpty");
        assertThat(events.get(1).path("turn").asInt()).isEqualTo(2);
        assertThat(events.get(0).path("at").asText()).isNotBlank();
    }

    @Test
    void 리포트는_응시당_최신_한_건() {
        assertThat(store.readReport(id)).isNull();
        store.writeReport(id, json.createObjectNode().put("total", 70));
        store.writeReport(id, json.createObjectNode().put("total", 80));
        assertThat(store.readReport(id).path("total").asInt()).isEqualTo(80);
    }

    @Test
    void 없는_응시는_NoSuchElement() {
        assertThatThrownBy(() -> store.load("999999")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> store.load("abc")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 이벤트_이름_변환() {
        assertThat(DbAttemptStore.dbType("firstValid")).isEqualTo("FIRST_VALID");
        assertThat(DbAttemptStore.appType("FIRST_VALID")).isEqualTo("firstValid");
        assertThat(DbAttemptStore.appType(DbAttemptStore.dbType("followups"))).isEqualTo("followups");
    }
}
