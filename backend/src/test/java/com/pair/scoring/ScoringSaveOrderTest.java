package com.pair.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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

import com.pair.attempt.Attempt;
import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptRegistry;
import com.pair.attempt.AttemptStore;
import com.pair.grader.PromptVersion;
import com.pair.scoring.ScoringService.CheckResult;
import com.pair.scoring.ScoringService.ItemResult;
import com.pair.scoring.ScoringService.JudgeCall;
import com.pair.scoring.db.CheckResultRecordRepository;
import com.pair.scoring.db.ScoringRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;

/** 채점 결과 저장 순서: 리포트 JSON → 채점 표 DONE → 응시 DONE. 중간에 실패하면 채점은 ERROR (모델 호출 없음) */
@SpringBootTest
@ActiveProfiles("examtest")
class ScoringSaveOrderTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired ScoringService scoring;
    @Autowired ScoringRecords records;
    @Autowired AttemptStore store;
    @Autowired AttemptRegistry registry;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired com.pair.scoring.db.PromptVersionRecordRepository promptVersions;
    @Autowired ScoringRecordRepository scorings;
    @Autowired CheckResultRecordRepository results;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final PromptVersion version = new PromptVersion("20261120-1000_order-test", "order", null, now, null, "규칙", Map.of());
    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords);
        TestPromptVersions.ensure(promptVersions, version);
        AttemptRecord r = registry.save(AttemptRecord.self(1L, 1L, "gpt-test", now));
        id = String.valueOf(r.getId());
        store.create(new Attempt(id, TestTasks.SELF_DEV, "openai", "gpt-test", now, null, null, null,
                Attempt.Status.SCORING, List.of(), null, null), Map.of("src/A.java", "class A {}"));
        store.save(store.load(id).withSubmitted(now.plusSeconds(1)));
        store.save(store.load(id).withFollowUps(List.of("답"), List.of("질문"), now.plusSeconds(2))); // 채점 중(SCORING)
    }

    private Map<String, Object> report(Object extra) {
        CheckResult u0 = new CheckResult("U0", "라벨", "AUTO", 20, 0, "PASSED", List.of(), null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 2);
        m.put("total", 100);
        m.put("items", List.of(new ItemResult("usage", "AI 활용력", 20, 20, List.of(u0))));
        m.put("judgeCalls", List.<JudgeCall>of());
        if (extra != null) m.put("bad", extra);
        return m;
    }

    @Test
    void 리포트_저장이_실패하면_채점은_ERROR고_결과가_남지_않는다() {
        long sid = records.start(Long.parseLong(id), version);
        // JSON 으로 바꿀 수 없는 값을 넣어 리포트 저장을 실패시킨다
        assertThatThrownBy(() -> scoring.saveResult(sid, id, report(new Object()))).isInstanceOf(IllegalStateException.class);

        assertThat(scorings.findById(sid).orElseThrow().getStatus()).isEqualTo("ERROR");
        assertThat(results.findByScoringId(sid)).isEmpty();
        assertThat(store.load(id).status()).isNotEqualTo(Attempt.Status.DONE);
    }

    @Test
    void 저장이_끝나면_채점과_응시가_DONE_이다() {
        long sid = records.start(Long.parseLong(id), version);
        scoring.saveResult(sid, id, report(null));

        assertThat(scorings.findById(sid).orElseThrow().getStatus()).isEqualTo("DONE");
        assertThat(results.findByScoringId(sid)).hasSize(1);
        assertThat(store.load(id).status()).isEqualTo(Attempt.Status.DONE);
    }
}
