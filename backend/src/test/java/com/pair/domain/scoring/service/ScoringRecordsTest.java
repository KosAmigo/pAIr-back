package com.pair.domain.scoring.service;

import com.pair.domain.scoring.service.TestPromptVersions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.service.AttemptRegistry;
import com.pair.domain.grader.service.PromptVersion;
import com.pair.domain.scoring.service.ScoringService.CheckResult;
import com.pair.domain.scoring.service.ScoringService.ItemResult;
import com.pair.domain.scoring.service.ScoringService.JudgeCall;
import com.pair.domain.scoring.service.ScoringService.JudgeView;
import com.pair.domain.scoring.entity.CheckResultRecord;
import com.pair.domain.scoring.repository.CheckResultRecordRepository;
import com.pair.domain.scoring.entity.JudgeCallRecord;
import com.pair.domain.scoring.repository.JudgeCallRecordRepository;
import com.pair.domain.scoring.entity.JudgeVerdictRecord;
import com.pair.domain.scoring.repository.JudgeVerdictRecordRepository;
import com.pair.domain.scoring.repository.PromptCheckHintRepository;
import com.pair.domain.scoring.entity.PromptVersionRecord;
import com.pair.domain.scoring.repository.PromptVersionRecordRepository;
import com.pair.domain.scoring.repository.RubricCheckRecordRepository;
import com.pair.domain.scoring.repository.RubricItemRecordRepository;
import com.pair.domain.scoring.entity.ScoringRecord;
import com.pair.domain.scoring.repository.ScoringRecordRepository;
import com.pair.domain.task.repository.TaskRecordRepository;
import com.pair.domain.task.TestTasks;

/** 실제 응시 채점 결과가 채점 테이블에 저장되는지 (H2, 채점 모델 호출 없음) */
@SpringBootTest
@ActiveProfiles("examtest")
class ScoringRecordsTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired ScoringRecords records;
    @Autowired ScoringCatalog catalog;
    @Autowired AttemptRegistry registry;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired RubricItemRecordRepository items;
    @Autowired RubricCheckRecordRepository checks;
    @Autowired PromptVersionRecordRepository versions;
    @Autowired PromptCheckHintRepository hints;
    @Autowired ScoringRecordRepository scorings;
    @Autowired JudgeCallRecordRepository calls;
    @Autowired CheckResultRecordRepository results;
    @Autowired JudgeVerdictRecordRepository verdicts;

    long attemptId;
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final PromptVersion version = new PromptVersion("20261119-1402_v6-test", "v6", "메모", now, null,
            "공통 규칙", Map.of("U1", new PromptVersion.CheckPrompt("새 판정 기준", 4)));

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords);
        TestPromptVersions.ensure(versions, version);
        attemptId = registry.save(AttemptRecord.self(1L, 1L, "gpt-test", now)).getId();
    }

    @Test
    void 채점_기준표가_DB에_맞춰진다() {
        catalog.syncRubric();
        catalog.syncRubric(); // 두 번 해도 늘지 않는다
        assertThat(items.count()).isEqualTo(6);
        assertThat(checks.count()).isEqualTo(Rubric.CHECKS.size());
        assertThat(checks.findByCodeAndTrack("D1", "DEV")).isPresent();
        assertThat(checks.findByCodeAndTrack("D1", "BIZ")).isPresent();
        assertThat(checks.findByCodeAndTrack("U1", "ALL").get().getMethod()).isEqualTo("JUDGE");
    }

    @Test
    void 버전_id는_name으로_행을_찾기만_한다() {
        long a = catalog.versionId(version);
        assertThat(catalog.versionId(version)).isEqualTo(a);
        assertThat(versions.findById(a).orElseThrow().getName()).isEqualTo(version.id());
        long before = versions.count();
        assertThatThrownBy(() -> catalog.versionId(new PromptVersion("없는-버전", "x", null, now, null, "규칙", Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(versions.count()).isEqualTo(before); // 없는 버전을 새로 넣지 않는다
    }

    @Test
    void 채점_결과를_저장한다() {
        long sid = records.start(attemptId, version);
        assertThat(scorings.findById(sid).orElseThrow().getStatus()).isEqualTo("RUNNING");

        String longRef = "x".repeat(80);
        List<JudgeView> views = List.of(
                new JudgeView("claude", "haiku", true, 0, List.of("#T01", longRef), "목표 없음"),
                new JudgeView("openai#1", "terra", false, 0, List.of(), "괜찮음"));
        CheckResult u1 = new CheckResult("U1", "라벨", "JUDGE", 4, 4, "DEDUCTED", List.of("#T01"), "목표 없음", views);
        CheckResult u0 = new CheckResult("U0", "라벨", "AUTO", 20, 0, "PASSED", List.of(), null);
        CheckResult d1 = new CheckResult("D1", "라벨", "AUTO", 5, 10, "DEDUCTED", List.of(), "테스트 2개 실패");
        List<ItemResult> list = List.of(
                new ItemResult("usage", "AI 활용력", 20, 16, List.of(u0, u1)),
                new ItemResult("delivery", "AI 결과 도출력", 30, 20, List.of(d1)));
        List<JudgeCall> judgeCalls = List.of(
                new JudgeCall("claude", "haiku", 100, 10, 50),
                new JudgeCall("openai#1", "terra", 200, 20, 0));

        records.finish(sid, true, list, judgeCalls);

        ScoringRecord s = scorings.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo("DONE");
        assertThat(s.getFinishedAt()).isNotNull();

        List<CheckResultRecord> rs = results.findByScoringId(sid);
        assertThat(rs).hasSize(3);
        long d1Dev = checks.findByCodeAndTrack("D1", "DEV").orElseThrow().getId();
        assertThat(rs).anySatisfy(r -> {
            assertThat(r.getCheckId()).isEqualTo(d1Dev);
            assertThat(r.getDeducted()).isEqualTo(10);
            assertThat(r.getStatus()).isEqualTo("DEDUCTED");
            assertThat(r.getReason()).isEqualTo("테스트 2개 실패");
        });
        long u1Id = checks.findByCodeAndTrack("U1", "ALL").orElseThrow().getId();
        CheckResultRecord u1Rec = rs.stream().filter(r -> r.getCheckId() == u1Id).findFirst().orElseThrow();
        assertThat(u1Rec.getDeducted()).isEqualTo(4);

        List<JudgeCallRecord> cs = calls.findByScoringId(sid);
        assertThat(cs).hasSize(2);
        JudgeCallRecord haiku = cs.stream().filter(c -> c.getModel().equals("haiku")).findFirst().orElseThrow();
        JudgeCallRecord terra = cs.stream().filter(c -> c.getModel().equals("terra")).findFirst().orElseThrow();
        assertThat(haiku.getInputTokens()).isEqualTo(100);
        assertThat(haiku.getCachedTokens()).isEqualTo(50);

        List<JudgeVerdictRecord> vs = verdicts.findByResultId(u1Rec.getId());
        assertThat(vs).hasSize(2);
        JudgeVerdictRecord vh = vs.stream().filter(v -> v.getJudgeCallId().equals(haiku.getId())).findFirst().orElseThrow();
        JudgeVerdictRecord vt = vs.stream().filter(v -> v.getJudgeCallId().equals(terra.getId())).findFirst().orElseThrow();
        assertThat(vh.isViolated()).isTrue();
        assertThat(vh.getEvidences()).containsExactlyInAnyOrder("#T01", "x".repeat(50));
        assertThat(vt.isViolated()).isFalse();
        assertThat(vt.getEvidences()).isEmpty();
    }

    @Test
    void 실패하면_ERROR와_메시지가_남는다() {
        long sid = records.start(attemptId, version);
        records.fail(sid, "채점 모델이 모두 실패");
        ScoringRecord s = scorings.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo("ERROR");
        assertThat(s.getError()).isEqualTo("채점 모델이 모두 실패");
        assertThat(s.getFinishedAt()).isNotNull();
    }
}
