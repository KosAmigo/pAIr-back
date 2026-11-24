package com.pair.domain.grader.repository;

import org.junit.jupiter.api.Tag;

import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.grader.service.LegacyPromptImportService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.scoring.service.RubricService;
import com.pair.domain.scoring.service.ScoringCatalogService;
import com.pair.domain.scoring.repository.ScoringResultRepository;
import com.pair.domain.scoring.service.ScoringService;
import com.pair.domain.scoring.repository.PromptCheckHintRepository;
import com.pair.domain.scoring.repository.PromptVersionRepository;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;

/** 프롬프트 버전 원본이 DB 인지: 저장·조회, 활성 하나, 옛 파일 가져오기, 채점의 version_id (H2, 모델 호출 없음) */
@SpringBootTest
@ActiveProfiles("examtest")
@Tag("slow")
class PromptVersionStateRepositoryTest {

    @TempDir
    static Path dataDir;
    @TempDir
    static Path graderDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("pair.grader.dir", () -> graderDir.toString()); // 진짜 grader 폴더를 건드리지 않는다
    }

    @Autowired PromptVersionStateRepository store;
    @Autowired LegacyPromptImportService importer;
    @Autowired PromptVersionRepository versions;
    @Autowired PromptCheckHintRepository hints;
    @Autowired ScoringService scoring;
    @Autowired ScoringResultRepository records;
    @Autowired ScoringCatalogService catalog;
    @Autowired ScoringRepository scorings;
    @Autowired AttemptRegistryService registry;
    @Autowired TaskRepository taskRecords;
    @Autowired ObjectMapper json;

    private PromptVersionStateDto draft(String name, String parent, String hint) {
        Map<String, PromptVersionStateDto.CheckPrompt> checks = new LinkedHashMap<>();
        checks.put("U1", new PromptVersionStateDto.CheckPrompt(hint, 99)); // 배점은 무시되고 기준표 값이 저장된다
        return new PromptVersionStateDto(null, name, "메모 " + name, null, parent, "공통 규칙 " + name, checks);
    }

    @Test
    void 시작하면_내장_기본_버전이_들어가고_활성이_하나다() {
        assertThat(store.list()).extracting(PromptVersionStateDto::id).contains(PromptVersionStateRepository.DEFAULT_ID);
        assertThat(versions.findByActiveTrue()).hasSize(1);
        assertThat(store.activeId()).isNotBlank();
    }

    @Test
    void 저장한_버전을_목록과_조회에서_똑같이_읽는다() {
        PromptVersionStateDto base = store.create(draft("저장 테스트", null, "  새 판정 기준  "));
        PromptVersionStateDto child = store.create(draft("저장 테스트", base.id(), null));

        PromptVersionStateDto read = store.get(base.id());
        assertThat(read).isEqualTo(base);
        assertThat(read.name()).isEqualTo("저장 테스트");
        assertThat(read.note()).isEqualTo("메모 저장 테스트");
        assertThat(read.judgeSystem()).isEqualTo("공통 규칙 저장 테스트");
        assertThat(read.checks().get("U1").hint()).isEqualTo("새 판정 기준"); // 앞뒤 공백 제거
        int u1Points = RubricService.CHECKS.stream().filter(c -> RubricService.key(c).equals("U1")).findFirst().orElseThrow().points();
        assertThat(read.checks().get("U1").points()).isEqualTo(u1Points);
        assertThat(read.checks()).hasSize(RubricService.CHECKS.size());
        assertThat(read.checks().values().stream().filter(c -> c.hint() != null).count())
                .isEqualTo(RubricService.CHECKS.stream().filter(c -> c.method() == RubricService.Method.JUDGE).count()); // 판정 체크만 기준 문장이 있다

        assertThat(child.id()).isEqualTo(base.id() + "-2"); // 같은 이름이면 -2
        assertThat(child.parent()).isEqualTo(base.id());
        assertThat(child.checks().get("U1").hint()).isNotBlank(); // 비우면 코드 기본값

        List<String> ids = store.list().stream().map(PromptVersionStateDto::id).toList();
        assertThat(store.list()).contains(base, child);
        assertThat(ids.indexOf(base.id())).isLessThan(ids.indexOf(child.id())); // 만든 순서

        assertThatThrownBy(() -> store.get("없는-버전")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> store.create(draft("  ", null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 활성_변경은_하나만_남긴다() {
        PromptVersionStateDto a = store.create(draft("활성 A", null, null));
        PromptVersionStateDto b = store.create(draft("활성 B", null, null));
        store.setActive(a.id());
        assertThat(store.activeId()).isEqualTo(a.id());
        store.setActive(b.id());
        store.setActive(b.id()); // 같은 것을 또 켜도 그대로
        assertThat(store.activeId()).isEqualTo(b.id());
        assertThat(store.active().id()).isEqualTo(b.id());
        assertThat(versions.findByActiveTrue()).hasSize(1);
        assertThatThrownBy(() -> store.setActive("없는-버전")).isInstanceOf(NoSuchElementException.class);
        assertThat(store.activeId()).isEqualTo(b.id()); // 실패해도 활성은 그대로
    }

    @Test
    void 옛_파일_가져오기는_여러_번_돌려도_중복이_없다() throws Exception {
        Path vdir = Files.createDirectories(graderDir.resolve("versions"));
        PromptVersionStateDto parent = new PromptVersionStateDto("20260101-0900_legacy-a", "옛 A", "메모A", Instant.parse("2026-01-01T00:00:00Z"), null,
                "규칙A", Map.of("U1", new PromptVersionStateDto.CheckPrompt("옛 기준", 4)));
        PromptVersionStateDto child = new PromptVersionStateDto("20260102-0900_legacy-b", "옛 B", null, Instant.parse("2026-01-02T00:00:00Z"),
                parent.id(), "규칙B", Map.of());
        // 파일 이름 순서와 생성 시각 순서가 달라도 parent 가 이어져야 한다
        Files.writeString(vdir.resolve("0.json"), json.writeValueAsString(child));
        Files.writeString(vdir.resolve("1.json"), json.writeValueAsString(parent));
        Files.writeString(vdir.resolve("broken.json"), "{ 깨진 파일");
        Files.writeString(graderDir.resolve("active.txt"), parent.id());
        long before = versions.count();
        String activeBefore = store.activeId(); // 이미 활성이 있으므로 active.txt 로 바뀌지 않는다

        assertThat(importer.importFiles()).isEqualTo(2);
        assertThat(importer.importFiles()).isZero();
        assertThat(importer.importFiles()).isZero();

        assertThat(versions.count()).isEqualTo(before + 2);
        assertThat(store.activeId()).isEqualTo(activeBefore);
        assertThat(store.get(parent.id()).checks().get("U1").hint()).isEqualTo("옛 기준");
        assertThat(store.get(parent.id()).createdAt()).isEqualTo(parent.createdAt());
        assertThat(store.get(child.id()).parent()).isEqualTo(parent.id());
        assertThat(store.get(child.id()).checks().get("U1").hint()).isNotBlank(); // 비어 있던 기준은 코드 기본값으로
        long parentRow = versions.findFirstByNameOrderByIdAsc(parent.id()).orElseThrow().getId();
        long judgeCount = RubricService.CHECKS.stream().filter(c -> c.method() == RubricService.Method.JUDGE).count();
        assertThat(hints.findByVersionId(parentRow)).hasSize((int) judgeCount); // 판정 기준 행도 한 벌만
    }

    @Test
    void 채점은_활성_버전의_행_id를_남긴다() {
        TestTasks.seed(taskRecords);
        PromptVersionStateDto v = store.create(draft("채점 연결", null, null));
        store.setActive(v.id());
        long attemptId = registry.save(Attempt.self(1L, 1L, "gpt-test", Instant.now())).getId();

        PromptVersionStateDto used = scoring.defaultOptions().version();
        assertThat(used.id()).isEqualTo(v.id());
        long sid = records.start(attemptId, used);

        long expected = versions.findFirstByNameOrderByIdAsc(v.id()).orElseThrow().getId();
        assertThat(scorings.findById(sid).orElseThrow().getVersionId()).isEqualTo(expected);
        assertThat(catalog.versionId(used)).isEqualTo(expected);
        assertThat(versions.findByActiveTrue()).hasSize(1); // 채점이 활성을 바꾸지 않는다
    }
}
