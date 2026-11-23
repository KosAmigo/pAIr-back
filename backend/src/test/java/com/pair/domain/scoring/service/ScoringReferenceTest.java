package com.pair.domain.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.infra.ai.EmbeddingClient;
import com.pair.infra.ai.Judge;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.domain.task.entity.DocumentPage;
import com.pair.domain.task.repository.DocumentPageRepository;
import com.pair.domain.task.service.TaskCatalogService;
import com.pair.domain.task.entity.TaskFile;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.repository.TaskDefRepository;
import com.pair.domain.task.TestTasks;

/** 비개발 채점의 제공 자료: 과제 원본(PDF 포함)에서 가져오고, auto 로 full(캐시 앞부분)/rag(뒷부분)를 고른다. AI·임베딩은 가짜. */
@SpringBootTest
@ActiveProfiles("examtest")
class ScoringReferenceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    /** "가격"이 들어간 글은 (1,0), 아니면 (0,1) */
    @MockitoBean EmbeddingClient embeddings;

    @Autowired ScoringService scoring;
    @Autowired ReferenceDocService referenceDocs;
    @Autowired TaskDefRepository tasks;
    @Autowired TaskCatalogService catalog;
    @Autowired AttemptStateRepository store;
    @Autowired TaskRepository taskRecords;
    @Autowired TaskFileRepository taskFiles;
    @Autowired TaskQuestionRepository taskQuestions;
    @Autowired DocumentPageRepository pages;
    @Autowired com.pair.domain.task.repository.QuestionSourceRepository sourceRecords;
    @Autowired com.pair.domain.attempt.service.AttemptRegistryService registry;

    static final String BIG = "big-biz";
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final PromptVersionStateDto version = new PromptVersionStateDto("20261120-1100_ref-test", "ref", null, now, null, "규칙", Map.of());

    @BeforeEach
    void setUp() {
        pages.deleteAll();
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        // pricing-plan(2번): PDF 자료 1개(2쪽) + 숨은 자료 + 자료 밖 공개 파일을 더한다
        long small = 2L;
        TaskFile pdf = pdf(small, "자료/설문보고서.pdf");
        pages.save(new DocumentPage(pdf.getId(), 2, "둘째 쪽 글: 가격 인상 여파"));
        pages.save(new DocumentPage(pdf.getId(), 1, "첫 쪽 글: 이탈 사유"));
        taskFiles.save(new TaskFile(small, "자료/숨김.md", "숨은 자료 글", "text/markdown", 10, TaskFile.HIDDEN, true));
        taskFiles.save(new TaskFile(small, "참고/밖의파일.md", "자료 밖 글", "text/markdown", 10, TaskFile.VISIBLE, false));
        // 큰 자료 과제: PDF 6쪽 × 2500자 = 15000자 (> 12000)
        Task big = new Task(60L, BIG, "큰 자료 과제", "BIZ", 60, Task.Usage.SELF, Task.Status.OPEN, null, 1L, now);
        big.setContent("설명", "요구", "{\"openFile\":\"기획안.md\",\"editableFile\":null,\"requiredHeadings\":[],\"d4Minutes\":[10,20,30,40]}");
        taskRecords.save(big);
        taskFiles.deleteAll(taskFiles.findAll().stream().filter(f -> f.getTaskId() == 60L).toList());
        taskFiles.save(new TaskFile(60L, "기획안.md", "", "text/markdown", 0, TaskFile.VISIBLE, false));
        taskFiles.save(new TaskFile(60L, "checklist.md", "체크", "text/markdown", 10, TaskFile.HIDDEN, true));
        TaskFile bigPdf = pdf(60L, "자료/큰보고서.pdf");
        for (int i = 1; i <= 6; i++) {
            String filler = ("쪽" + i + " 내용 ").repeat(2500 / 6);
            pages.save(new DocumentPage(bigPdf.getId(), i, (i == 6 ? "가격 " : "") + filler));
        }
        // 함정 정보(_gen/report.json)와 문항 근거(question_sources): 큰 자료 과제에만 있다
        taskQuestions.deleteAll(taskQuestions.findAll().stream().filter(q -> q.getTaskId() == 60L).toList());
        sourceRecords.deleteAll();
        taskFiles.save(new TaskFile(60L, "_gen/report.json",
                "{\"details\":{\"trap\":{\"description\":\"6쪽의 단서를 놓치면 가격 인상이 이득처럼 보인다\",\"evidence\":\"\\\"쪽6 내용 쪽6 내용\\\"\"}}}",
                "application/json", 100, TaskFile.HIDDEN, true));
        var q1 = taskQuestions.save(new TaskQuestion(60L, "TASK", "SHORT", 1, "문항 하나"));
        var q2 = taskQuestions.save(new TaskQuestion(60L, "TASK", "SINGLE", 2, "문항 둘"));
        sourceRecords.save(new com.pair.domain.task.entity.QuestionSource(q1.getId(), bigPdf.getId(), "5,6"));
        sourceRecords.save(new com.pair.domain.task.entity.QuestionSource(q2.getId(), bigPdf.getId(), null));
        when(embeddings.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> v = new ArrayList<>();
            for (String t : texts) v.add(t.contains("가격") ? new float[]{1, 0} : new float[]{0, 1});
            return new EmbeddingClient.Result(v, texts.size());
        });
    }

    private TaskFile pdf(long taskId, String path) {
        TaskFile f = new TaskFile(taskId, path, null, "application/pdf", 1000, TaskFile.VISIBLE, true);
        f.setStorageKey("tasks/" + taskId + "/" + path);
        return taskFiles.save(f);
    }

    // ── materials ─────────────────

    @Test
    void 자료는_텍스트와_PDF_쪽_글을_경로_순으로_돌려준다() {
        Map<String, String> m = tasks.materials(TestTasks.SELF_BIZ);
        assertThat(m.keySet()).containsExactly("자료/설문보고서.pdf", "자료/요금제별_매출.csv", "자료/이탈_설문_요약.md");
        assertThat(m.get("자료/설문보고서.pdf")).isEqualTo("--- 1쪽 ---\n첫 쪽 글: 이탈 사유\n--- 2쪽 ---\n둘째 쪽 글: 가격 인상 여파\n");
        assertThat(m.get("자료/요금제별_매출.csv")).isEqualTo(tasks.visibleFiles(TestTasks.SELF_BIZ).get("자료/요금제별_매출.csv"));
        // 숨은 파일, 자료/ 밖 파일은 없다
        assertThat(String.join("", m.values())).doesNotContain("숨은 자료 글").doesNotContain("자료 밖 글");
    }

    @Test
    void 실전_복사본은_원본_자료를_쓴다() {
        String code = catalog.definitionCode(catalog.require(TestTasks.LIVE_BIZ));
        assertThat(code).isEqualTo(TestTasks.SELF_BIZ);
        assertThat(tasks.materials(code)).isEqualTo(tasks.materials(TestTasks.SELF_BIZ)).containsKey("자료/설문보고서.pdf");
    }

    // ── useRag ────────────────────

    @Test
    void auto_기준과_강제_선택() {
        Map<String, String> m = Map.of("자료/a.md", "x".repeat(100));
        assertThat(ReferenceDocService.useRag(m, "auto", 100)).isFalse(); // 기준 이하
        assertThat(ReferenceDocService.useRag(m, "auto", 99)).isTrue();   // 초과
        assertThat(ReferenceDocService.useRag(m, "full", 1)).isFalse();
        assertThat(ReferenceDocService.useRag(m, "rag", 1_000_000)).isTrue();
        assertThat(ReferenceDocService.useRag(Map.of(), "rag", 1)).isFalse(); // 자료가 없으면 full
    }

    // ── 채점 프롬프트 ─────────────

    record Prompt(String system, String prefix, String body) {}

    private String newAttempt(String taskCode, long taskId, Map<String, String> files) {
        var r = registry.save(com.pair.domain.attempt.entity.Attempt.self(1L, taskId, "gpt-test", now));
        String id = String.valueOf(r.getId());
        store.create(new AttemptStateDto(id, taskCode, "openai", "gpt-test", now, null, null, null,
                AttemptStateDto.Status.SCORING, List.of(), null, null), files);
        store.save(store.load(id).withSubmitted(now.plusSeconds(1)));
        return id;
    }

    /** 가짜 채점 모델: 받은 프롬프트를 모아 두고 빈 판정을 돌려준다. 실제 API 호출 없음. */
    private Map<String, Object> run(String attemptId, List<Prompt> seen) {
        ScoringService.Calls fake = (Judge base, int repeat, String system, String prefix, String body) -> {
            synchronized (seen) {
                seen.add(new Prompt(system, prefix, body));
            }
            return new Judge.Result<>(new ScoringService.JudgeResult(List.of()), 0, 0, 0);
        };
        return scoring.evaluate(store, attemptId, new ScoringService.Options(version, 1), fake);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ref(Map<String, Object> report) {
        return (Map<String, Object>) report.get("referenceDocs");
    }

    @Test
    void 자료가_작으면_full_이고_같은_과제_두_응시의_prefix가_글자까지_같다() {
        String a1 = newAttempt(TestTasks.SELF_BIZ, 2L, Map.of("기획안.md", "# 기획안\n가격은 5,900원이다."));
        String a2 = newAttempt(TestTasks.SELF_BIZ, 2L, Map.of("기획안.md", "# 전혀 다른 기획안\n이탈률은 7.9%다."));
        List<Prompt> p1 = Collections.synchronizedList(new ArrayList<>());
        List<Prompt> p2 = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> r1 = run(a1, p1);
        run(a2, p2);

        assertThat(ref(r1)).containsEntry("mode", "full").containsEntry("inCachedPrefix", true).containsEntry("setting", "auto");
        assertThat(p1).isNotEmpty();
        // 같은 응시의 모든 호출, 그리고 다른 응시의 prefix 까지 똑같다 (프롬프트 캐시가 맞는 조건)
        assertThat(p1.stream().map(Prompt::prefix).distinct()).hasSize(1);
        assertThat(p1.get(0).prefix()).isEqualTo(p2.get(0).prefix());
        assertThat(p1.get(0).prefix()).contains("# 제공 자료").contains("--- 1쪽 ---\n첫 쪽 글: 이탈 사유").contains("월,");
        assertThat(p1.get(0).prefix().indexOf("설문보고서.pdf")).isLessThan(p1.get(0).prefix().indexOf("요금제별_매출.csv")); // 경로 순
        assertThat(p1.get(0).body()).doesNotContain("# 제공 자료").doesNotContain("첫 쪽 글");
        assertThat(p1.get(0).prefix()).doesNotContain("숨은 자료 글").doesNotContain("자료 밖 글");
    }

    @Test
    void 응시자가_자료_파일을_고쳐도_채점에는_원본_자료가_들어간다() {
        String original = tasks.visibleFiles(TestTasks.SELF_BIZ).get("자료/요금제별_매출.csv");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("기획안.md", "# 기획안\n내용");
        files.put("자료/요금제별_매출.csv", "월,가입자\n10월,TAMPERED");
        List<Prompt> seen = Collections.synchronizedList(new ArrayList<>());
        run(newAttempt(TestTasks.SELF_BIZ, 2L, files), seen);

        String all = seen.get(0).prefix() + seen.get(0).body();
        assertThat(all).contains(original).doesNotContain("TAMPERED");
    }

    @Test
    void 자료가_기준을_넘으면_rag_이고_조각은_body에_들어간다() {
        String id = newAttempt(BIG, 60L, Map.of("기획안.md", "# 기획안\n가격은 5,900원이다."));
        List<Prompt> seen = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> report = run(id, seen);

        assertThat(ref(report)).containsEntry("mode", "rag").containsEntry("inCachedPrefix", false);
        assertThat((Integer) ref(report).get("chars")).isLessThan(15000);
        assertThat(seen.get(0).prefix()).doesNotContain("# 제공 자료");
        assertThat(seen.get(0).body()).contains("# 제공 자료").contains("가격");
    }

    // ── 함정·근거 ─────────────────

    @Test
    void 함정이_있는_과제의_prefix에는_근거_문장과_위치가_들어가고_두_응시가_같다() {
        List<Prompt> p1 = Collections.synchronizedList(new ArrayList<>());
        List<Prompt> p2 = Collections.synchronizedList(new ArrayList<>());
        run(newAttempt(BIG, 60L, Map.of("기획안.md", "# 기획안\n가격은 5,900원이다.")), p1);
        run(newAttempt(BIG, 60L, Map.of("기획안.md", "# 다른 기획안\n매출은 100만원이다.")), p2);

        String prefix = p1.get(0).prefix();
        assertThat(prefix).contains("## 함정과 근거 (응시자는 보지 못함)")
                .contains("- 함정: 6쪽의 단서를 놓치면 가격 인상이 이득처럼 보인다")
                .contains("- 근거 문장: \"쪽6 내용 쪽6 내용\"")
                .contains("- 근거 위치: 자료/큰보고서.pdf 6쪽")
                .contains("- 문항 1: 자료/큰보고서.pdf 5,6쪽")
                .contains("- 문항 2: 자료/큰보고서.pdf");
        // 숨은 체크리스트 바로 뒤, 체크 항목 앞
        assertThat(prefix.indexOf("## 숨은 체크리스트")).isLessThan(prefix.indexOf("## 함정과 근거"));
        assertThat(prefix.indexOf("## 함정과 근거")).isLessThan(prefix.indexOf("# 판정할 체크 항목"));
        assertThat(p2.get(0).prefix()).isEqualTo(prefix);
        assertThat(p1.get(0).body()).doesNotContain("## 함정과 근거");
    }

    @Test
    void 함정_정보가_없는_과제에는_절이_없다() {
        List<Prompt> seen = Collections.synchronizedList(new ArrayList<>());
        run(newAttempt(TestTasks.SELF_BIZ, 2L, Map.of("기획안.md", "# 기획안")), seen);
        assertThat(seen.get(0).prefix()).doesNotContain("함정과 근거");
        assertThat(tasks.trapNotes(TestTasks.SELF_BIZ)).isNull();
    }

    // ── 글자 수 비교 (출력용) ─────

    @Test
    void 글자_수_비교를_출력한다() {
        StringBuilder out = new StringBuilder("\n[자료 글자 수 비교: 자료 부분 / 프롬프트 전체(근사)]\n");
        for (var c : List.of(List.of(TestTasks.SELF_BIZ, "2", "가격은 5,900원이다."), List.of(BIG, "60", "가격은 5,900원이다."))) {
            String code = c.get(0);
            Map<String, String> m = tasks.materials(code);
            String sub = "# 기획안\n" + c.get(2);
            int full = referenceDocs.build(m, sub, false).chars();
            int rag = referenceDocs.build(m, sub, true).chars();
            boolean autoRag = ReferenceDocService.useRag(m, "auto", 12000);
            List<Prompt> seen = Collections.synchronizedList(new ArrayList<>());
            run(newAttempt(code, Long.parseLong(c.get(1)), Map.of("기획안.md", sub)), seen);
            Prompt p = seen.get(0);
            int base = p.system().length() + p.prefix().length() + p.body().length() - (autoRag ? rag : full);
            out.append(String.format("%s | 자료 원문 %d자 | full 자료 %d / 전체 %d | rag 자료 %d / 전체 %d | auto=%s%n",
                    code, m.values().stream().mapToInt(String::length).sum(), full, base + full, rag, base + rag, autoRag ? "rag" : "full"));
        }
        String notes = tasks.trapNotes(BIG);
        out.append(String.format("함정·근거 절(big-biz): %d자%n", notes == null ? 0 : notes.length()));
        System.out.println(out);
    }
}
