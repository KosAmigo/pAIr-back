package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.FillerDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E6·M6: 과제별 AI 제한 기본값, 단독 풀이의 앞에서부터 첨부, 분량 검사(필요한 묶음 ≤ 70%, 전체 ≥ 2배), 단계 4' 입력·다듬기, 설계도 묶음 검사, 파이프라인 연결 */
class MaterialLimitTest {

    private static final TaskGenProperties E6 = new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, true);
    private static final TaskGenRequest DEV_FORM = new TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20);
    private static final TaskGenRequest BIZ_FORM = new TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20);

    /** 헷갈리는 자료로 표시한 관련 적은 자료(설계도 검사는 1/3 이상을 요구한다) */
    private static BlueprintDto.Filler conf(String path, String summary) {
        return new BlueprintDto.Filler(path, summary, true);
    }

    /** 필요한 묶음이 한도(8000자)의 40% 이상이 되도록 부풀린 시작 코드·공개 테스트 */
    private static com.pair.domain.taskgen.dto.DevCodeDto paddedCode() {
        BlueprintDto bp = TaskPipelineServiceTest.devBlueprint();
        List<String> evidence = new java.util.ArrayList<>();
        bp.cases().forEach(c -> evidence.add(c.basis()));
        bp.flaws().forEach(f -> evidence.add(f.evidence()));
        bp.judgments().forEach(j -> evidence.add(j.basis()));
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) (i % 2 == 0 ? a : b).append("    String e").append(i).append(" = \"").append(evidence.get(i).replace("\"", "'")).append("\";\n");
        String pad = "    String note = \"" + "x".repeat(1500) + "\";\n";
        return new com.pair.domain.taskgen.dto.DevCodeDto(List.of(new DevDraftDto.FileDraft("src/shop/PaymentDiscount.java", "package shop;\npublic class PaymentDiscount {\n" + pad + a + "}\n"),
                new DevDraftDto.FileDraft("test/shop/PaymentDiscountTest.java", "package shop;\nclass PaymentDiscountTest {\n" + pad + b + "}\n")));
    }

    // ── 과제별 AI 제한 기본값 ─────────────────────────────

    @Test
    @DisplayName("기본 제한: 20분 10/2/8000, 40분 16/3/12000, 60분 24/3/16000")
    void defaultLimitsByMinutes() {
        assertThat(AiLimitsDto.forMinutes(20)).isEqualTo(new AiLimitsDto(10, 2, 8000));
        assertThat(AiLimitsDto.forMinutes(40)).isEqualTo(new AiLimitsDto(16, 3, 12000));
        assertThat(AiLimitsDto.forMinutes(60)).isEqualTo(new AiLimitsDto(24, 3, 16000));
    }

    @Test
    @DisplayName("저장된 값이 하나라도 없거나 0 이하면 제한 없음(null) → 전역 설정을 쓴다")
    void missingValuesMeanNoLimit() {
        assertThat(AiLimitsDto.ofOrNull(10, 2, 8000)).isEqualTo(new AiLimitsDto(10, 2, 8000));
        assertThat(AiLimitsDto.ofOrNull(null, 2, 8000)).isNull();
        assertThat(AiLimitsDto.ofOrNull(10, 0, 8000)).isNull();
    }

    // ── AI 단독 풀이: 앞에서부터 한도까지 ─────────────────────────────

    @Test
    @DisplayName("앞에서부터 붙인다: 개수 한도를 넘는 파일은 못 붙이고, 글자 한도에서 마지막 파일이 잘린다")
    void attachInOrderUpToLimits() {
        var all = List.of(new SoloLimitService.Material("a", "1".repeat(60)), new SoloLimitService.Material("b", "2".repeat(60)), new SoloLimitService.Material("c", "3".repeat(10)));

        var byCount = SoloLimitService.attach(all, new AiLimitsDto(10, 2, 1000));
        assertThat(byCount.attached()).extracting(SoloLimitService.Material::path).containsExactly("a", "b");
        assertThat(byCount.omitted()).containsExactly("c");

        var byChars = SoloLimitService.attach(all, new AiLimitsDto(10, 3, 100));
        assertThat(byChars.attached().get(1).text()).hasSize(40); // 100 - 60
        assertThat(byChars.omitted()).containsExactly("c");
        assertThat(byChars.chars()).isEqualTo(100);

        assertThat(SoloLimitService.attach(all, null).attached()).hasSize(3); // 제한 없음
    }

    @Test
    @DisplayName("DEV 단독 풀이 글: 제한이 있으면 파일을 앞에서부터 한도까지만 넣고, 없으면 전부 넣는다")
    void devSoloPromptRespectsLimits() {
        DevDraftDto d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(TaskGenFixtures.f("src/shop/A.java", "AAAA".repeat(25)),
                TaskGenFixtures.f("src/shop/B.java", "BBBB".repeat(25)), TaskGenFixtures.f("test/shop/ATest.java", "CCCC")));

        String limited = SoloSolveService.prompt(d, new AiLimitsDto(10, 1, 50));
        assertThat(limited).contains("[파일 src/shop/A.java]").doesNotContain("src/shop/B.java").doesNotContain("ATest");
        assertThat(limited).contains("A".repeat(50)).doesNotContain("A".repeat(51));

        String unlimited = SoloSolveService.prompt(d);
        assertThat(unlimited).contains("src/shop/A.java").contains("src/shop/B.java").contains("ATest");
    }

    @Test
    @DisplayName("BIZ 단독 풀이 글: 문서·CSV 를 앞에서부터 한도까지 붙이고 문항은 그대로 넣는다")
    void bizSoloPromptRespectsLimits() {
        BizDraftDto d = BizFixtures.good("campaign");
        String limited = BizSoloSolveService.prompt(d, new AiLimitsDto(10, 1, 120));
        List<String> names = new java.util.ArrayList<>(d.documents().stream().map(BizDraftDto.DocDraft::path).toList());
        d.csvFiles().forEach(f -> names.add(f.path()));
        names.sort(String.CASE_INSENSITIVE_ORDER); // 이름순(응시 화면 파일 목록과 같음)으로 앞에서부터 붙인다
        assertThat(limited).contains("[자료 " + names.get(0));
        names.stream().skip(1).forEach(n -> assertThat(limited).doesNotContain("[자료 " + n));
        assertThat(limited).contains("[문항]").contains("Q1.");
        String unlimited = BizSoloSolveService.prompt(d);
        assertThat(unlimited).contains(d.documents().get(1).path()).contains(d.csvFiles().get(0).path());
    }

    // ── M6 결정적 검사 ─────────────────────────────

    private static BlueprintDto bp(List<String> needed, List<BlueprintDto.Filler> fillers) {
        return TaskPipelineServiceTest.devBlueprint().withMaterials(needed, fillers);
    }

    @Test
    @DisplayName("M6 필요한 묶음: 한도의 70% 이하면 통과, 넘으면 실패. DEV 는 코드를 다시 쓰고(g14), BIZ 는 설계도를 다시 만든다")
    void neededBundleAtMost70Percent() {
        BlueprintDto b = bp(List.of("src/a.java", "src/b.java"), List.of(conf("src/f1.java", "로그 형식")));
        AiLimitsDto limit = new AiLimitsDto(10, 2, 1000);

        Map<String, String> ok = Map.of("src/a.java", "x".repeat(400), "src/b.java", "x".repeat(300));
        assertThat(MaterialGateService.needed(b, ok, limit).ok()).isTrue(); // 700 = 70%
        assertThat(MaterialGateService.needed(b, ok, limit).detail()).contains("파일: ").contains("src/a.java 400자").contains("src/b.java 300자"); // 통과해도 판정이 쓸 파일 목록(g15)
        Map<String, String> big = Map.of("src/a.java", "x".repeat(400), "src/b.java", "x".repeat(301));
        GenCheckResultDto fail = MaterialGateService.needed(b, big, limit);
        assertThat(fail.ok()).isFalse();
        assertThat(fail.name()).isEqualTo(TaskPipelineService.M6_NEEDED_CHECK);
        assertThat(DevPipelineService.owners(fail, List.of(), List.of())).containsExactly(Stage.CODE); // NOTES.md 가 없으니 코드만
        assertThat(BizPipelineService.owners(fail)).containsExactly(Stage.BLUEPRINT);
    }

    @Test
    @DisplayName("단독 풀이는 이름순으로 붙인다: 필요한 파일이 목록 앞에 있어도 앞에서부터 고르기가 곧 정답 고르기가 되지 않는다")
    void attachInNameOrder() {
        var all = List.of(new SoloLimitService.Material("src/shop/Zeta.java", "z"), new SoloLimitService.Material("test/shop/Test.java", "t"),
                new SoloLimitService.Material("NOTES.md", "n"), new SoloLimitService.Material("src/shop/alpha.java", "a"));
        var r = SoloLimitService.attach(all, new AiLimitsDto(10, 4, 1000));
        assertThat(r.attached()).extracting(SoloLimitService.Material::path).containsExactly("src/shop/alpha.java", "src/shop/Zeta.java", "test/shop/Test.java", "NOTES.md");
    }

    @Test
    @DisplayName("M6 필요한 묶음 40%: 미만이면 경고만 낸다(다시 만들지 않는다)")
    void neededBundleAtLeast40Percent() {
        BlueprintDto b = bp(List.of("src/a.java", "src/b.java"), List.of(conf("src/f1.java", "로그 형식")));
        AiLimitsDto limit = new AiLimitsDto(10, 2, 1000);
        assertThat(MaterialGateService.neededMin(b, Map.of("src/a.java", "x".repeat(200), "src/b.java", "x".repeat(200)), limit).ok()).isTrue(); // 400 = 40%
        GenCheckResultDto warn = MaterialGateService.neededMin(b, Map.of("src/a.java", "x".repeat(200), "src/b.java", "x".repeat(199)), limit);
        assertThat(warn.ok()).isFalse();
        assertThat(warn.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(warn.needsRegeneration()).isFalse();
        assertThat(warn.detail()).contains("40%");
        // 70% 검사(실패 조건)는 작은 묶음을 실패로 보지 않는다
        assertThat(MaterialGateService.needed(b, Map.of("src/a.java", "x", "src/b.java", "x"), limit).ok()).isTrue();
    }

    private static BlueprintDto withFacts(BlueprintDto b, List<BlueprintDto.Fact> facts) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), facts, b.examples(), b.flaws(), b.judgments(), b.trapKeywords(),
                b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases(), b.neededFiles(), b.fillers());
    }

    @Test
    @DisplayName("M6 근거 파일: 결함 위치 파일과 사례 근거 파일을 합쳐 필요한 파일 2개 이상이면 통과, 아니면 경고만")
    void evidenceInTwoNeededFiles() {
        // g7: 고정 설계도의 결함 위치는 api 밖 클래스(DiscountLedger.add)
        BlueprintDto base = TaskPipelineServiceTest.devBlueprint().withMaterials(List.of("src/shop/DiscountLedger.java", "NOTES.md"), List.of(conf("src/shop/F.java", "로그")));
        // 사례 근거가 설명(description)에 있으면 필요한 파일이 아니라서 결함 위치 파일 1개뿐
        GenCheckResultDto one = MaterialGateService.evidence(base);
        assertThat(one.ok()).isFalse();
        assertThat(one.name()).isEqualTo(TaskPipelineService.M6_EVIDENCE_CHECK);
        assertThat(one.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(one.needsRegeneration()).isFalse();
        assertThat(one.detail()).contains("1개");
        // 사례 근거 사실이 NOTES.md 에 있으면 코드 파일(결함 위치) + NOTES.md = 2개
        String basis = base.cases().get(0).basis();
        List<BlueprintDto.Fact> moved = base.facts().stream().map(f -> f.text().equals(basis) ? new BlueprintDto.Fact(f.text(), "NOTES.md") : f).toList();
        GenCheckResultDto two = MaterialGateService.evidence(withFacts(base, moved));
        assertThat(two.ok()).isTrue();
        assertThat(two.detail()).contains("2개");
    }

    @Test
    @DisplayName("M6 헷갈리는 자료: 관련 적은 자료의 1/3 미만이면 경고만 낸다")
    void blueprintConfusingShare() {
        var plain = List.of(new BlueprintDto.Filler("src/f1.java", "a"), new BlueprintDto.Filler("src/f2.java", "b"), new BlueprintDto.Filler("src/f3.java", "c"), new BlueprintDto.Filler("src/f4.java", "d"));
        GenCheckResultDto warn = MaterialGateService.confusing(bp(List.of("src/a.java", "test/aTest.java"), plain));
        assertThat(warn.ok()).isFalse();
        assertThat(warn.needsRegeneration()).isFalse();
        assertThat(warn.name()).isEqualTo(TaskPipelineService.M6_CONFUSING_CHECK);
        var two = List.of(conf("src/f1.java", "a"), conf("src/f2.java", "b"), plain.get(2), plain.get(3));
        assertThat(MaterialGateService.confusing(bp(List.of("src/a.java", "test/aTest.java"), two)).ok()).isTrue();
        // 설계도 단계는 더 이상 헷갈리는 자료 수로 다시 만들게 하지 않는다
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java", "test/aTest.java"), plain), false, 20)).isEmpty();
        assertThat(MaterialGateService.confusingMin(4)).isEqualTo(2);
        assertThat(MaterialGateService.confusingMin(3)).isEqualTo(1);
        assertThat(MaterialGateService.confusingMin(6)).isEqualTo(2);
    }

    @Test
    @DisplayName("단계 4' 입력은 파일마다 따로 만들고, 헷갈리는 자료는 표시한다")
    void fillerInputsPerFile() {
        BlueprintDto b = bp(List.of("src/a.java"), List.of(conf("src/f1.java", "옛 정책"), new BlueprintDto.Filler("src/f2.java", "로그 형식")));
        List<String> in = FillerService.inputs(b, 3000, "");
        assertThat(in).hasSize(2);
        assertThat(in.get(0)).contains("src/f1.java").contains("[헷갈리는 자료]");
        assertThat(in.get(1)).contains("src/f2.java").doesNotContain("[헷갈리는 자료]");
        assertThat(in.get(0)).doesNotContain("만들 파일: 이 파일 하나만 만든다]\n- src/f2.java");
    }

    @Test
    @DisplayName("M6 필요한 묶음: 파일이 안 만들어졌으면 DEV 는 시작 코드 단계, NOTES.md 는 작성 단계를 다시")
    void neededFileMissing() {
        BlueprintDto b = bp(List.of("src/a.java", "NOTES.md"), List.of());
        GenCheckResultDto missingCode = MaterialGateService.needed(b, Map.of("NOTES.md", "m"), new AiLimitsDto(10, 2, 1000));
        assertThat(missingCode.ok()).isFalse();
        assertThat(DevPipelineService.owners(missingCode, List.of(), List.of())).containsExactly(Stage.CODE);
        GenCheckResultDto missingNotes = MaterialGateService.needed(b, Map.of("src/a.java", "m"), new AiLimitsDto(10, 2, 1000));
        assertThat(DevPipelineService.owners(missingNotes, List.of(), List.of())).containsExactly(Stage.WRITING);
    }

    @Test
    @DisplayName("M6 자료 전체: 한도의 2배 이상이면 통과, 모자라면 실패해 단계 4' 만 다시 만든다")
    void totalAtLeastTwiceTheLimit() {
        BlueprintDto b = bp(List.of("src/a.java"), List.of(conf("src/f1.java", "로그 형식")));
        AiLimitsDto limit = new AiLimitsDto(10, 2, 1000);

        assertThat(MaterialGateService.total(b, Map.of("src/a.java", "x".repeat(500), "src/f1.java", "x".repeat(1500)), limit).ok()).isTrue(); // 2000
        GenCheckResultDto fail = MaterialGateService.total(b, Map.of("src/a.java", "x".repeat(500), "src/f1.java", "x".repeat(1499)), limit);
        assertThat(fail.ok()).isFalse();
        assertThat(fail.detail()).contains("2000").contains("부족 약 1자");
        assertThat(DevPipelineService.owners(fail, List.of(), List.of())).containsExactly(Stage.FILLER);
        assertThat(BizPipelineService.owners(fail)).containsExactly(Stage.FILLER);
        // 설계도가 정한 관련 적은 자료 파일이 빠져도 실패
        GenCheckResultDto missing = MaterialGateService.total(b, Map.of("src/a.java", "x".repeat(3000)), limit);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.detail()).contains("src/f1.java");
    }

    @Test
    @DisplayName("관련 적은 자료 분량: 자료 전체가 한도의 2.3배가 되도록 파일마다 나눠 정한다")
    void perFillerChars() {
        BlueprintDto b = bp(List.of("src/a.java"), List.of(conf("src/f1.java", "a"), conf("src/f2.java", "b")));
        // 8000 × 2.3 = 18400, 필요한 묶음 4000 → 14400 / 2 = 7200
        assertThat(MaterialGateService.perFillerChars(b, 4000, new AiLimitsDto(10, 2, 8000))).isEqualTo(7200);
    }

    // ── 단계 4' 입력·다듬기 ─────────────────────────────

    @Test
    @DisplayName("단계 4' 입력에는 시나리오·관련 적은 자료 목록·필요한 묶음 이름만 있고 결함·사실·사례는 없다")
    void fillerInputHidesFlaw() {
        BlueprintDto b = bp(List.of("src/shop/PaymentDiscount.java"), List.of(conf("src/shop/AuditLog.java", "감사 로그 기록")));
        String input = FillerService.input(b, 3000, "");
        assertThat(input).contains(b.scenario()).contains("src/shop/AuditLog.java").contains("감사 로그 기록").contains("src/shop/PaymentDiscount.java").contains("3000");
        for (String secret : List.of(b.flaws().get(0).what(), b.flaws().get(0).where(), "totalsExactly", "double", "부동소수점", TaskPipelineServiceTest.F_SETTLE, TaskPipelineServiceTest.EX1_IN)) {
            assertThat(input).as(secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("단계 4' 결과 다듬기: 설계도가 정한 경로만, 빈 파일·중복은 버린다")
    void fillerSanitized() {
        BlueprintDto b = bp(List.of("src/a.java"), List.of(conf("src/f1.java", "a"), conf("src/f2.java", "b")));
        FillerDto raw = new FillerDto(List.of(new DevDraftDto.FileDraft("src/f1.java", "class F1 {}"), new DevDraftDto.FileDraft("src/f1.java", "다른 것"),
                new DevDraftDto.FileDraft("src/f2.java", "  "), new DevDraftDto.FileDraft("src/other.java", "class O {}")));
        FillerDto clean = FillerService.sanitized(raw, b);
        assertThat(clean.files()).extracting(DevDraftDto.FileDraft::path).containsExactly("src/f1.java");
        assertThat(clean.files().get(0).content()).isEqualTo("class F1 {}");
    }

    @Test
    @DisplayName("BIZ 문서는 코드가 쪽으로 나눈다: 쪽마다 글자 수 상한 안")
    void paginate() {
        String text = "회의록\n\n" + ("월간 집행 점검 내용을 정리했다. 담당자는 일정과 예산을 확인했다.\n\n").repeat(40);
        List<String> pages = FillerService.paginate(text);
        assertThat(pages.size()).isGreaterThan(3);
        assertThat(pages).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(FillerService.PAGE_CHARS));
        assertThat(String.join("", pages).replaceAll("\\s", "")).isEqualTo(text.replaceAll("\\s", ""));
    }

    // ── 설계도 묶음 검사(6장 시간별 분량) ─────────────────────────────

    @Test
    @DisplayName("설계도: 20분 DEV 는 필요한 묶음 2~3개, 관련 적은 자료로 파일 6~8개 안팎이어야 한다")
    void blueprintMaterialRangeDev() {
        var fillers = List.of(conf("src/f1.java", "a"), conf("src/f2.java", "b"), conf("src/f3.java", "c"),
                conf("src/f4.java", "d"));
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java", "test/aTest.java"), fillers), false, 20)).isEmpty();
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java"), fillers), false, 20)).anyMatch(m -> m.contains("neededFiles"));
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java", "test/aTest.java"), List.of()), false, 20)).anyMatch(m -> m.contains("fillers"));
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java", "src/f1.java"), fillers), false, 20)).anyMatch(m -> m.contains("같은 경로"));
        assertThat(BlueprintService.materialProblems(bp(List.of("src/a.java", "test/aTest.java"), List.of(conf("src/f1.java", ""), conf("src/f2.java", "b"),
                conf("src/f3.java", "c"), conf("src/f4.java", "d"))), false, 20)).anyMatch(m -> m.contains("summary"));
    }

    @Test
    @DisplayName("설계도: BIZ 필요한 묶음은 문서 계획·표 경로 안에서 고르고, 관련 적은 자료는 자료/ 아래 pdf·csv")
    void blueprintMaterialRangeBiz() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint(); // docs 2 + tables 1
        var fillers = List.of(conf("자료/지난 회의록.pdf", "a"), conf("자료/타 캠페인 집행.csv", "b"));
        assertThat(BlueprintService.materialProblems(b.withMaterials(List.of("자료/검토 안내.pdf", "자료/운영 메모.pdf"), fillers), true, 20)).isEmpty();
        assertThat(BlueprintService.materialProblems(b.withMaterials(List.of("자료/검토 안내.pdf", "자료/없는 문서.pdf"), fillers), true, 20)).anyMatch(m -> m.contains("없습니다"));
        assertThat(BlueprintService.materialProblems(b.withMaterials(List.of("자료/검토 안내.pdf", "자료/운영 메모.pdf"), List.of(conf("src/x.java", "a"), fillers.get(1))), true, 20))
                .anyMatch(m -> m.contains("경로 형식"));
    }

    // ── 파이프라인 연결 ─────────────────────────────

    private static FillerDto devFiller(int charsEach) {
        List<DevDraftDto.FileDraft> files = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) files.add(new DevDraftDto.FileDraft("src/shop/Filler" + i + ".java", "package shop;\nclass Filler" + i + " { String s = \"" + "x".repeat(charsEach) + "\"; }\n"));
        return new FillerDto(files);
    }

    private static BlueprintDto devE6Blueprint() {
        return TaskPipelineServiceTest.devBlueprint().withMaterials(List.of("src/shop/PaymentDiscount.java", "test/shop/PaymentDiscountTest.java"),
                List.of(conf("src/shop/Filler1.java", "로그 형식"), conf("src/shop/Filler2.java", "설정 읽기"),
                        conf("src/shop/Filler3.java", "영수증 포맷"), conf("src/shop/Filler4.java", "회원 등급")));
    }

    private static TaskPipelineServiceTest.StageAi devAi(FillerDto filler) {
        return new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, devE6Blueprint()).add(com.pair.domain.taskgen.dto.DevWritingDto.class, TaskPipelineServiceTest.devWriting())
                .add(com.pair.domain.taskgen.dto.DevCodeDto.class, paddedCode()).add(com.pair.domain.taskgen.dto.DevAnswerDto.class, TaskPipelineServiceTest.devAnswer())
                .add(FillerDto.class, filler);
    }

    @Test
    @DisplayName("DEV 파이프라인(M6 켜짐): 정답 쪽 뒤에 싼 모델이 관련 적은 자료를 만들고 M6 검사가 통과한다")
    void devPipelineWithFiller() {
        var ai = devAi(devFiller(4200));
        var run = new DevPipelineService(ai, E6).start(DEV_FORM);
        DevDraftDto d = run.create(s -> {});

        assertThat(ai.order).containsExactly(BlueprintDto.class, com.pair.domain.taskgen.dto.DevWritingDto.class, com.pair.domain.taskgen.dto.DevCodeDto.class,
                com.pair.domain.taskgen.dto.DevAnswerDto.class, FillerDto.class, FillerDto.class, FillerDto.class, FillerDto.class); // 관련 적은 자료 4개를 파일마다 따로 부른다
        assertThat(ai.models.get(4)).isEqualTo("gpt-6.1-terra"); // 싼 모델(설정 기본값)
        assertThat(run.usage()).extracting("stage").contains("FILLER");
        assertThat(ai.users.get(4)).doesNotContain("double", "부동소수점", "totalsExactly");
        assertThat(d.files()).extracting(DevDraftDto.FileDraft::path).contains("src/shop/Filler1.java", "src/shop/Filler4.java");
        assertThat(run.checks()).filteredOn(c -> c.name().equals(TaskPipelineService.M6_NEEDED_CHECK) || c.name().equals(TaskPipelineService.M6_TOTAL_CHECK))
                .hasSize(2).allSatisfy(c -> assertThat(c.ok()).as(c.detail()).isTrue());
    }

    @Test
    @DisplayName("DEV 파이프라인(M6 켜짐): 자료 전체가 모자라면 M6 검사가 실패하고 다시 만들 단계는 단계 4' 하나뿐이다")
    void devPipelineTotalTooSmallRedoesFillerOnly() {
        var ai = devAi(devFiller(100));
        var run = new DevPipelineService(ai, E6).start(DEV_FORM);
        run.create(s -> {});

        var failed = run.checks().stream().filter(GenCheckResultDto::needsRegeneration).toList();
        assertThat(failed).extracting(GenCheckResultDto::name).contains(TaskPipelineService.M6_TOTAL_CHECK);
        assertThat(run.plannedStages(failed)).containsExactly("FILLER");
        assertThat(run.restartsFrom(failed)).isFalse();

        ai.script.get(FillerDto.class).clear();
        ai.script.get(FillerDto.class).add(devFiller(4200));
        int calls = ai.order.size();
        run.redo(failed, s -> {});
        assertThat(ai.order.subList(calls, ai.order.size())).containsOnly(FillerDto.class).hasSize(4); // 설계도·작성·코드·정답은 다시 안 돈다
        assertThat(ai.users.get(ai.users.size() - 1)).contains("자료 전체"); // 앞서 실패한 이유를 알려 준다
        assertThat(run.checks().stream().filter(GenCheckResultDto::needsRegeneration).toList()).isEmpty();
    }

    @Test
    @DisplayName("M6 를 끄면(기본) 단계 4' 는 돌지 않고 M6 검사도 없다")
    void gateOffKeepsOldBehavior() {
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.devBlueprint())
                .add(com.pair.domain.taskgen.dto.DevWritingDto.class, TaskPipelineServiceTest.devWriting()).add(com.pair.domain.taskgen.dto.DevCodeDto.class, TaskPipelineServiceTest.devCode())
                .add(com.pair.domain.taskgen.dto.DevAnswerDto.class, TaskPipelineServiceTest.devAnswer());
        var run = new DevPipelineService(ai, new TaskGenProperties(null, null, null, null, null)).start(DEV_FORM);
        run.create(s -> {});
        assertThat(ai.order).doesNotContain(FillerDto.class);
        assertThat(run.checks()).extracting(GenCheckResultDto::name).doesNotContain(TaskPipelineService.M6_NEEDED_CHECK, TaskPipelineService.M6_TOTAL_CHECK);
    }

    @Test
    @DisplayName("BIZ 파이프라인(M6 켜짐): 관련 적은 문서·CSV 가 문서 쪽과 CSV 로 붙고 M6 검사가 통과한다")
    void bizPipelineWithFiller() {
        BlueprintDto b = TaskPipelineServiceTest.bizBlueprint().withMaterials(List.of("자료/검토 안내.pdf", "자료/운영 메모.pdf"),
                List.of(conf("자료/지난 회의록.pdf", "지난 분기 회의"), conf("자료/타 캠페인 집행.csv", "다른 캠페인 집행표")));
        String longText = ("집행 점검 회의에서 일정과 예산 집행 현황을 확인하고 다음 점검 일정을 정했다.\n\n").repeat(260);
        String csv = "캠페인,월,금액\n" + "가을,9월,1200\n".repeat(900);
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, b).add(com.pair.domain.taskgen.dto.BizWritingDto.class, TaskPipelineServiceTest.bizWriting(" " + "가".repeat(3600)))
                .add(com.pair.domain.taskgen.dto.BizQuestionsDto.class, TaskPipelineServiceTest.bizQuestions()).add(com.pair.domain.taskgen.dto.BizAnswerDto.class, TaskPipelineServiceTest.bizAnswer())
                .add(FillerDto.class, new FillerDto(List.of(new DevDraftDto.FileDraft("자료/지난 회의록.pdf", longText), new DevDraftDto.FileDraft("자료/타 캠페인 집행.csv", csv))));
        var run = new BizPipelineService(ai, E6).start(BIZ_FORM);
        BizDraftDto d = run.create(s -> {});

        assertThat(ai.order).contains(FillerDto.class); // 관련 적은 자료는 설계도 직후 작성 등과 동시에 돈다(순서는 정해지지 않음)
        assertThat(d.documents()).extracting(BizDraftDto.DocDraft::path).containsExactly("자료/검토 안내.pdf", "자료/운영 메모.pdf", "자료/지난 회의록.pdf");
        assertThat(d.documents().get(2).pages().size()).isGreaterThan(5);
        assertThat(d.csvFiles()).extracting(DevDraftDto.FileDraft::path).containsExactly("자료/캠페인 실적.csv", "자료/타 캠페인 집행.csv");
        var m6 = run.checks().stream().filter(c -> c.name().equals(TaskPipelineService.M6_NEEDED_CHECK) || c.name().equals(TaskPipelineService.M6_TOTAL_CHECK)).toList();
        assertThat(m6).hasSize(2).allSatisfy(c -> assertThat(c.ok()).as(c.detail()).isTrue());
    }

    // ── 관문(AI): 관련 적은 자료 ─────────────────────────────

    @Test
    @DisplayName("관련 적은 자료 점검 입력: 출제자만 아는 정보(결함·사례 기대 결과·판단 지점)와 관련 적은 자료 글, 자료가 없으면 점검하지 않는다")
    void fillerGateInput() {
        BlueprintDto b = devE6Blueprint();
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("src/shop/Filler1.java", "class Filler1 {}");
        String input = TaskReviewService.fillerInput(b, texts);
        assertThat(input).contains("[숨은 결함").contains("totalsExactly").contains("0.30").contains("[관련 적은 자료]").contains("[파일 src/shop/Filler1.java]").contains("class Filler1 {}");
        assertThat(TaskReviewService.fillerInput(b, Map.of())).isNull();
    }

    @Test
    @DisplayName("관련 적은 자료 점검은 DEV·BIZ 관문 목록에 있고 실패하면 단계 4' 만 다시 만든다. 생성(terra)과 다른 회사 모델(Sonnet)이 본다")
    void fillerGateRoutesToFiller() {
        assertThat(TaskReviewService.gates(true)).anyMatch(g -> g.name().equals(TaskPipelineService.FILLER_GATE) && g.stage() == Stage.FILLER_REVIEW);
        assertThat(TaskReviewService.gates(false)).anyMatch(g -> g.name().equals(TaskPipelineService.FILLER_GATE));
        var fail = new GenCheckResultDto(TaskPipelineService.FILLER_GATE, false, "M6 [src/shop/Filler1.java] 인용 → 이 문장 없이 다시 쓴다");
        assertThat(DevPipelineService.owners(fail, List.of(), List.of())).containsExactly(Stage.FILLER);
        assertThat(BizPipelineService.owners(fail)).containsExactly(Stage.FILLER);
        assertThat(E6.stage(true, "FILLER").model()).isEqualTo("gpt-6.1-terra");
        assertThat(E6.stage(true, "FILLER_REVIEW").model()).isEqualTo("claude-sonnet-5-5");
        assertThat(E6.stage(false, "FILLER_REVIEW").model()).isEqualTo("claude-sonnet-5-5");
        assertThat(TaskPromptService.check("filler-leak")).contains("M6");
    }
}
