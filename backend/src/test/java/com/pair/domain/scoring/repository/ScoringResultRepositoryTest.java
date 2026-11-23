package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.service.RubricService;
import com.pair.domain.scoring.service.ScoringCatalogService;
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

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.scoring.service.ScoringService.CheckOutcome;
import com.pair.domain.scoring.service.ScoringService.ItemResult;
import com.pair.domain.scoring.service.ScoringService.JudgeUsage;
import com.pair.domain.scoring.service.ScoringService.JudgeView;
import com.pair.domain.scoring.entity.CheckResult;
import com.pair.domain.scoring.entity.JudgeCall;
import com.pair.domain.scoring.entity.JudgeVerdict;
import com.pair.domain.scoring.entity.PromptVersion;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;

/** 실제 응시 채점 결과가 채점 테이블에 저장되는지 (H2, 채점 모델 호출 없음) */
@SpringBootTest
@ActiveProfiles("examtest")
class ScoringResultRepositoryTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired ScoringResultRepository records;
    @Autowired ScoringCatalogService catalog;
    @Autowired AttemptRegistryService registry;
    @Autowired TaskRepository taskRecords;
    @Autowired RubricItemRepository items;
    @Autowired RubricCheckRepository checks;
    @Autowired PromptVersionRepository versions;
    @Autowired PromptCheckHintRepository hints;
    @Autowired ScoringRepository scorings;
    @Autowired JudgeCallRepository calls;
    @Autowired CheckResultRepository results;
    @Autowired JudgeVerdictRepository verdicts;

    long attemptId;
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final PromptVersionStateDto version = new PromptVersionStateDto("20261119-1402_v6-test", "v6", "메모", now, null,
            "공통 규칙", Map.of("U1", new PromptVersionStateDto.CheckPrompt("새 판정 기준", 4)));

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords);
        TestPromptVersions.ensure(versions, version);
        attemptId = registry.save(Attempt.self(1L, 1L, "gpt-test", now)).getId();
    }

    @Test
    void 채점_기준표가_DB에_맞춰진다() {
        catalog.syncRubric();
        catalog.syncRubric(); // 두 번 해도 늘지 않는다
        assertThat(items.count()).isEqualTo(6);
        assertThat(checks.count()).isEqualTo(RubricService.CHECKS.size());
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
        assertThatThrownBy(() -> catalog.versionId(new PromptVersionStateDto("없는-버전", "x", null, now, null, "규칙", Map.of())))
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
        CheckOutcome u1 = new CheckOutcome("U1", "라벨", "JUDGE", 4, 4, "DEDUCTED", List.of("#T01"), "목표 없음", views);
        CheckOutcome u0 = new CheckOutcome("U0", "라벨", "AUTO", 20, 0, "PASSED", List.of(), null);
        CheckOutcome d1 = new CheckOutcome("D1", "라벨", "AUTO", 5, 10, "DEDUCTED", List.of(), "테스트 2개 실패");
        List<ItemResult> list = List.of(
                new ItemResult("usage", "AI 활용력", 20, 16, List.of(u0, u1)),
                new ItemResult("delivery", "AI 결과 도출력", 30, 20, List.of(d1)));
        List<JudgeUsage> judgeCalls = List.of(
                new JudgeUsage("claude", "haiku", 100, 10, 50),
                new JudgeUsage("openai#1", "terra", 200, 20, 0));

        records.finish(sid, true, list, judgeCalls);

        Scoring s = scorings.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo("DONE");
        assertThat(s.getFinishedAt()).isNotNull();

        List<CheckResult> rs = results.findByScoringId(sid);
        assertThat(rs).hasSize(3);
        long d1Dev = checks.findByCodeAndTrack("D1", "DEV").orElseThrow().getId();
        assertThat(rs).anySatisfy(r -> {
            assertThat(r.getCheckId()).isEqualTo(d1Dev);
            assertThat(r.getDeducted()).isEqualTo(10);
            assertThat(r.getStatus()).isEqualTo("DEDUCTED");
            assertThat(r.getReason()).isEqualTo("테스트 2개 실패");
        });
        long u1Id = checks.findByCodeAndTrack("U1", "ALL").orElseThrow().getId();
        CheckResult u1Rec = rs.stream().filter(r -> r.getCheckId() == u1Id).findFirst().orElseThrow();
        assertThat(u1Rec.getDeducted()).isEqualTo(4);

        List<JudgeCall> cs = calls.findByScoringId(sid);
        assertThat(cs).hasSize(2);
        JudgeCall haiku = cs.stream().filter(c -> c.getModel().equals("haiku")).findFirst().orElseThrow();
        JudgeCall terra = cs.stream().filter(c -> c.getModel().equals("terra")).findFirst().orElseThrow();
        assertThat(haiku.getInputTokens()).isEqualTo(100);
        assertThat(haiku.getCachedTokens()).isEqualTo(50);

        List<JudgeVerdict> vs = verdicts.findByResultId(u1Rec.getId());
        assertThat(vs).hasSize(2);
        JudgeVerdict vh = vs.stream().filter(v -> v.getJudgeCallId().equals(haiku.getId())).findFirst().orElseThrow();
        JudgeVerdict vt = vs.stream().filter(v -> v.getJudgeCallId().equals(terra.getId())).findFirst().orElseThrow();
        assertThat(vh.isViolated()).isTrue();
        assertThat(vh.getEvidences()).containsExactlyInAnyOrder("#T01", "x".repeat(50));
        assertThat(vt.isViolated()).isFalse();
        assertThat(vt.getEvidences()).isEmpty();
    }

    @Test
    void 실패하면_ERROR와_메시지가_남는다() {
        long sid = records.start(attemptId, version);
        records.fail(sid, "채점 모델이 모두 실패");
        Scoring s = scorings.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo("ERROR");
        assertThat(s.getError()).isEqualTo("채점 모델이 모두 실패");
        assertThat(s.getFinishedAt()).isNotNull();
    }
}
