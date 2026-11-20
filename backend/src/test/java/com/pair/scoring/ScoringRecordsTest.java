package com.pair.scoring;

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

import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptRegistry;
import com.pair.grader.PromptVersion;
import com.pair.scoring.ScoringService.CheckResult;
import com.pair.scoring.ScoringService.ItemResult;
import com.pair.scoring.ScoringService.JudgeCall;
import com.pair.scoring.ScoringService.JudgeView;
import com.pair.scoring.db.CheckResultRecord;
import com.pair.scoring.db.CheckResultRecordRepository;
import com.pair.scoring.db.JudgeCallRecord;
import com.pair.scoring.db.JudgeCallRecordRepository;
import com.pair.scoring.db.JudgeVerdictRecord;
import com.pair.scoring.db.JudgeVerdictRecordRepository;
import com.pair.scoring.db.PromptCheckHintRepository;
import com.pair.scoring.db.PromptVersionRecord;
import com.pair.scoring.db.PromptVersionRecordRepository;
import com.pair.scoring.db.RubricCheckRecordRepository;
import com.pair.scoring.db.RubricItemRecordRepository;
import com.pair.scoring.db.ScoringRecord;
import com.pair.scoring.db.ScoringRecordRepository;
import com.pair.task.TaskRecordRepository;
import com.pair.task.TestTasks;

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
    void 버전과_힌트는_한_번만_들어가고_활성이_된다() {
        long a = catalog.versionId(version);
        long b = catalog.versionId(version);
        assertThat(a).isEqualTo(b);
        assertThat(versions.findAll().stream().filter(v -> v.getName().equals(version.id()))).hasSize(1);
        long judgeCount = Rubric.CHECKS.stream().filter(c -> c.method() == Rubric.Method.JUDGE).count();
        assertThat(hints.findByVersionId(a)).hasSize((int) judgeCount);

        // 다른 버전을 쓰면 활성이 옮겨 가고, 바탕 버전이 이어진다
        PromptVersion child = new PromptVersion("20261119-1500_v7-test", "v7", null, null, version.id(), "규칙2", Map.of());
        long c = catalog.versionId(child);
        PromptVersionRecord parent = versions.findById(a).orElseThrow();
        PromptVersionRecord childRec = versions.findById(c).orElseThrow();
        assertThat(parent.isActive()).isFalse();
        assertThat(childRec.isActive()).isTrue();
        assertThat(childRec.getParentVersionId()).isEqualTo(a);
        assertThat(versions.findByActiveTrue()).hasSize(1);
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

    @Test
    void 이미_활성인_버전은_다시_불러도_아무것도_쓰지_않는다() {
        long a = catalog.versionId(version);
        PromptVersion other = new PromptVersion("20261120-0900_idem-test", "o", null, now, null, "규칙", Map.of());
        long b = catalog.versionId(other); // b 가 활성이 되고 a 는 꺼진다
        assertThat(versions.findById(a).orElseThrow().isActive()).isFalse();

        // 일부러 a 도 켜 둔다. b 를 다시 불렀을 때 쓰기가 일어난다면 a 가 다시 꺼질 것이다.
        PromptVersionRecord ra = versions.findById(a).orElseThrow();
        ra.setActive(true);
        versions.save(ra);

        assertThat(catalog.versionId(other)).isEqualTo(b);
        assertThat(versions.findById(a).orElseThrow().isActive()).isTrue();
    }
}
