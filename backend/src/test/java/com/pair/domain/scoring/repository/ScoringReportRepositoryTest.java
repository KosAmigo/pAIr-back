package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.service.TestPromptVersions;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.scoring.service.ScoringService.CheckOutcome;
import com.pair.domain.scoring.service.ScoringService.ItemResult;
import com.pair.domain.scoring.service.ScoringService.JudgeUsage;
import com.pair.domain.scoring.service.ScoringService.JudgeView;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;

/** 리포트를 읽을 때 점수와 판정이 채점 테이블 기준으로 나오는지 (H2) */
@SpringBootTest
@ActiveProfiles("examtest")
class ScoringReportRepositoryTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired ScoringResultRepository records;
    @Autowired AttemptStateRepository store;
    @Autowired AttemptRegistryService registry;
    @Autowired TaskRepository taskRecords;
    @Autowired com.pair.domain.scoring.repository.PromptVersionRepository promptVersions;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final PromptVersionStateDto version = new PromptVersionStateDto("20261119-1600_reader-test", "reader", null, now, null, "규칙", Map.of());
    long attemptId;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords);
        TestPromptVersions.ensure(promptVersions, version);
        attemptId = registry.save(Attempt.self(1L, 1L, "gpt-test", now)).getId();
    }

    @Test
    void 점수와_판정은_DB에서_읽고_나머지는_JSON에서_가져온다() {
        // JSON 에는 일부러 틀린 점수를 넣는다. DB 값이 이겨야 한다.
        store.writeReport(String.valueOf(attemptId), Map.of("version", 2, "total", 999, "notes", List.of("안내 문구"),
                "items", List.of()));

        long sid = records.start(attemptId, version);
        List<JudgeView> views = List.of(
                new JudgeView("claude", "haiku", true, 0, List.of("#T01"), "목표 없음"),
                new JudgeView("openai#1", "terra", true, 0, List.of("#T01"), "목표 없음"),
                new JudgeView("openai#2", "terra", false, 0, List.of(), "괜찮음"));
        CheckOutcome u1 = new CheckOutcome("U1", "라벨", "JUDGE", 4, 4, "DEDUCTED", List.of("#T01"), "목표 없음", views);
        CheckOutcome d1 = new CheckOutcome("D1", "라벨", "AUTO", 5, 10, "DEDUCTED", List.of(), "테스트 2개 실패");
        records.finish(sid, true, List.of(
                new ItemResult("usage", "AI 활용력", 20, 16, List.of(u1)),
                new ItemResult("delivery", "AI 결과 도출력", 30, 20, List.of(d1))), List.of(
                new JudgeUsage("claude", "haiku", 100, 10, 50),
                new JudgeUsage("openai#1", "terra", 200, 20, 0),
                new JudgeUsage("openai#2", "terra", 300, 30, 0)));

        JsonNode rep = store.readReport(String.valueOf(attemptId));

        assertThat(rep.path("notes").get(0).asText()).isEqualTo("안내 문구"); // JSON 에서
        assertThat(rep.path("total").asInt()).isEqualTo(100 - 4 - 10);
        assertThat(rep.path("max").asInt()).isEqualTo(100);
        assertThat(rep.path("promptVersion").asText()).isEqualTo(version.id());
        assertThat(rep.path("items")).hasSize(6);
        JsonNode usage = rep.path("items").get(0);
        assertThat(usage.path("key").asText()).isEqualTo("usage");
        assertThat(usage.path("score").asInt()).isEqualTo(16);
        JsonNode c = usage.path("checks").get(0);
        assertThat(c.path("code").asText()).isEqualTo("U1");
        assertThat(c.path("status").asText()).isEqualTo("DEDUCTED");
        assertThat(c.path("evidence")).hasSize(1);
        assertThat(c.path("judges")).hasSize(3);
        assertThat(c.path("judges").get(0).path("judge").asText()).isEqualTo("haiku");
        assertThat(c.path("judges").get(1).path("judge").asText()).isEqualTo("terra#1");
        assertThat(c.path("judges").get(2).path("violated").asBoolean()).isFalse();
        JsonNode delivery = rep.path("items").get(4);
        assertThat(delivery.path("key").asText()).isEqualTo("delivery");
        assertThat(delivery.path("score").asInt()).isEqualTo(20);
        assertThat(delivery.path("checks").get(0).path("code").asText()).isEqualTo("D1");
        assertThat(rep.path("judgeCalls")).hasSize(3);
        assertThat(rep.path("judgeCalls").get(0).path("cached").asInt()).isEqualTo(50);
    }

    @Test
    void 채점_기록이_없으면_JSON_그대로() {
        store.writeReport(String.valueOf(attemptId), Map.of("version", 2, "total", 77));
        JsonNode rep = store.readReport(String.valueOf(attemptId));
        assertThat(rep.path("total").asInt()).isEqualTo(77);
    }

    @Test
    void 실패한_채점은_무시한다() {
        store.writeReport(String.valueOf(attemptId), Map.of("version", 2, "total", 77));
        long sid = records.start(attemptId, version);
        records.fail(sid, "실패");
        assertThat(store.readReport(String.valueOf(attemptId)).path("total").asInt()).isEqualTo(77);
    }
}
