package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-26 g5 프롬프트 다시 쓰기(점검: taskgen-runs/_verify/prompt-audit-2026-11-26.md) */
class TaskPromptExampleTest {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 좋은 예를 직군별 설계도 모양(모델이 받는 스키마)으로 읽어 공통 설계도로 바꾼다 */
    private static BlueprintDto parse(String json, boolean biz) throws Exception {
        BlueprintDto b = biz ? JSON.readValue(json, com.pair.domain.taskgen.dto.BizBlueprintDto.class).toBlueprint()
                : JSON.readValue(json, com.pair.domain.taskgen.dto.DevBlueprintDto.class).toBlueprint();
        return BlueprintService.normalized(b, biz);
    }

    @Test
    @DisplayName("g6 직군별 설계도: DEV 프롬프트에는 BIZ 칸(숫자표·문서·문항)·BIZ 결함 유형이, BIZ 프롬프트에는 DEV 칸(공개 API·추가 메서드·separatedBy)이 없다. 체크리스트도 직군별")
    void blueprintPromptsAreTrackOnly() {
        String dev = TaskPromptService.devBlueprint(40, TaskPromptService.stageDepth(false, 40, true), true);
        String biz = TaskPromptService.bizBlueprint(40, TaskPromptService.stageDepth(true, 40, true), true);
        assertThat(dev).contains("api:").contains("separatedBy").contains("NOTES.md").contains("R1 ")
                .doesNotContain("tables:").doesNotContain("figures").doesNotContain("questionPlan").doesNotContain("B5 ").doesNotContain("자료/name.pdf");
        assertThat(biz).contains("tables:").contains("figures").contains("questionPlan").contains("B5 ")
                .doesNotContain("api:").doesNotContain("separatedBy").doesNotContain("추가 method").doesNotContain("R1 ").doesNotContain("NOTES.md");
        assertThat(dev).contains("11. No case basis equals").doesNotContain("15. fillers");
        assertThat(biz).contains("13. No case's expected value").contains("15. fillers");
    }

    @Test
    @DisplayName("g6-biz: 설명이 기획안.md 를 말해도 '설명에서 말한 파일이 실제로 있음'을 통과한다(저장할 때 만들어지는 틀). 문서별 작성은 문서 수 + 요청 글 1번 부른다")
    void planFileCountsAsVisible() {
        var base = TaskPipelineServiceTest.bizWriting("");
        var w = new com.pair.domain.taskgen.dto.BizWritingDto(List.of("캠페인 예산을 검토해 기획안.md 에 1페이지 기획안을 쓰세요."), base.requirements(), base.documents(), base.taskFollowUp());
        var ai = new TaskPipelineServiceTest.StageAi().add(BlueprintDto.class, TaskPipelineServiceTest.bizBlueprint()).add(com.pair.domain.taskgen.dto.BizWritingDto.class, w)
                .add(com.pair.domain.taskgen.dto.BizQuestionsDto.class, TaskPipelineServiceTest.bizQuestions()).add(com.pair.domain.taskgen.dto.BizAnswerDto.class, TaskPipelineServiceTest.bizAnswer());
        var run = new BizPipelineService(ai, new com.pair.global.config.TaskGenProperties(null, null, null, null, null)).start(new com.pair.domain.taskgen.dto.TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20));
        var d = run.create(s -> {});
        assertThat(run.checks()).filteredOn(c -> c.name().equals(TaskPipelineService.MENTION_CHECK)).singleElement().satisfies(c -> assertThat(c.ok()).isTrue());
        assertThat(d.documents()).extracting(com.pair.domain.taskgen.dto.BizDraftDto.DocDraft::path).containsExactly("자료/검토 안내.pdf", "자료/운영 메모.pdf");
        assertThat(run.usage()).filteredOn(u -> u.stage().equals("WRITING")).hasSize(3); // 문서 2 + 요청 글 1
    }

    @Test
    @DisplayName("g6 BIZ 작성 문서별: 문서 입력에는 그 문서 자리의 사실만, 요청 글 입력에는 description 자리 사실과 정해야 할 것만 들어가고 결함 정보는 없다")
    void bizWritingInputsSplitByDocument() throws Exception {
        BlueprintDto biz = parse(TaskPromptService.BIZ_EXAMPLE, true);
        var guide = biz.docs().get(0);
        String doc = TaskWritingService.bizDocInput(biz, guide, 40, "", BizPipelineService.DOC_CALL_MARK);
        String brief = TaskWritingService.bizBriefInput(biz, 40, "");
        for (var f : biz.facts()) {
            if (f.place().equals(guide.path())) assertThat(doc).contains(f.text());
            else assertThat(doc).doesNotContain(f.text());
            assertThat(brief).doesNotContain(f.text()); // 좋은 예에는 description 자리 사실이 없다
        }
        assertThat(doc).startsWith(BizPipelineService.DOC_CALL_MARK).contains("자료/캠페인 실적.csv").contains("10000");
        assertThat(brief).contains(biz.judgments().get(0).question());
        for (String s : List.of(doc, brief)) assertThat(s).doesNotContain(biz.flaws().get(0).what()).doesNotContain("퍼센트포인트").doesNotContain(biz.conclusion().correct());
    }

    @Test
    @DisplayName("점검 #12·13: 설계도 프롬프트의 좋은 예는 형식 검사와 E6·M6 목록 검사를 모두 통과한다(DEV 20분, BIZ 40분)")
    void examplesPassTheBlueprintChecks() throws Exception {
        BlueprintDto dev = parse(TaskPromptService.DEV_EXAMPLE, false);
        assertThat(BlueprintService.problems(dev, false)).isEmpty();
        assertThat(BlueprintService.materialProblems(dev, false, 20)).isEmpty();
        assertThat(MaterialGateService.evidence(dev).ok()).isTrue(); // 결함 위치(코드 파일) + notes 사실(NOTES.md)
        assertThat(MaterialGateService.confusing(dev).ok()).isTrue();

        BlueprintDto biz = parse(TaskPromptService.BIZ_EXAMPLE, true);
        assertThat(BlueprintService.problems(biz, true)).isEmpty();
        assertThat(BlueprintService.materialProblems(biz, true, 40)).isEmpty();
        assertThat(MaterialGateService.evidence(biz).ok()).isTrue();
    }

    @Test
    @DisplayName("점검 #1·2·18: 지시는 영어, 응시자 글 칸은 한국어로 쓰라고 하고, E6 이면 옛 분량 문장(파일 2~3개, 1,500~4,000자)이 없다. 체크리스트가 끝에 있다")
    void englishInstructionsKoreanOutput() {
        String dev = TaskPromptService.blueprint(false, 20, TaskPromptService.stageDepth(false, 20, true), true);
        assertThat(dev).contains("Write every text value in Korean").contains("Checklist").contains("[Flaw types]").contains("漢字")
                .doesNotContain("파일 2~3개").contains("neededFiles + fillers = 4–8");
        assertThat(dev.indexOf("Checklist")).isGreaterThan(dev.indexOf("Good example"));
        String biz = TaskPromptService.blueprint(true, 20, TaskPromptService.stageDepth(true, 20, true), true);
        assertThat(biz).doesNotContain("1,500~4,000").contains("docs + tables + fillers = 4–6").contains("No case's expected value appears in another case's choices");
        assertThat(TaskPromptService.writingBizDoc(20, true, 2)).doesNotContain("1,500–4,000").contains("1800–2400");
        assertThat(TaskPromptService.code("x", true, 20)).contains("Do not create NOTES.md").doesNotContain("근거 문장");
        for (String p : List.of(TaskPromptService.WRITING_DEV, TaskPromptService.writingBizDoc(40, true, 2), TaskPromptService.WRITING_BIZ_BRIEF, TaskPromptService.QUESTIONS_BIZ, TaskPromptService.answerDev(40),
                TaskPromptService.answerBiz("x"), TaskPromptService.filler(true))) {
            assertThat(p).contains("Never write Chinese characters");
        }
        assertThat(TaskPromptService.filler(false)).contains("String literals and identifiers in English or Korean only");
    }

    @Test
    @DisplayName("점검 #5: neededFiles 에 NOTES.md 가 있는데 notes 사실이 없으면 설계도 단계에서 바로 걸린다")
    void notesNeededWithoutNotesFact() throws Exception {
        BlueprintDto dev = parse(TaskPromptService.DEV_EXAMPLE, false);
        var facts = dev.facts().stream().map(f -> new BlueprintDto.Fact(f.text(), BlueprintService.DESCRIPTION)).toList();
        var noNotes = new BlueprintDto(dev.title(), dev.summary(), dev.code(), dev.category(), dev.competencies(), dev.scenario(), dev.api(), facts, dev.examples(),
                dev.flaws(), dev.judgments(), dev.trapKeywords(), dev.conclusion(), dev.tables(), dev.figures(), dev.docs(), dev.questionPlan(), dev.cases(),
                dev.neededFiles(), dev.fillers());
        assertThat(BlueprintService.materialProblems(noNotes, false, 20)).anyMatch(p -> p.contains("place 가 notes 인 사실"));
        assertThat(MaterialGateService.evidence(noNotes).ok()).isFalse(); // notes 가 없으면 코드 파일 1개뿐
    }

    @Test
    @DisplayName("g12: 사례 근거가 예 문장이면 사례 입력은 그 예와 같은 호출·값이어야 한다(g8 04: 근거는 allowed 예, 사례는 다른 이름·cachedAllowed)")
    void exampleBasisCaseUsesExampleInput() throws Exception {
        BlueprintDto dev = parse(TaskPromptService.DEV_EXAMPLE, false);
        assertThat(DevBlueprintService.exampleBasisCases(dev)).isEmpty();
        var cases = dev.cases().stream().map(c -> c.name().equals("payableAfterPoints")
                ? new BlueprintDto.Case(c.name(), "addItem(\"공책\", 7000, 3) 뒤 payable(500)", "20500", c.basis()) : c).toList();
        var changed = new BlueprintDto(dev.title(), dev.summary(), dev.code(), dev.category(), dev.competencies(), dev.scenario(), dev.api(), dev.facts(), dev.examples(),
                dev.flaws(), dev.judgments(), dev.trapKeywords(), dev.conclusion(), dev.tables(), dev.figures(), dev.docs(), dev.questionPlan(), cases,
                dev.neededFiles(), dev.fillers());
        assertThat(DevBlueprintService.exampleBasisCases(changed)).singleElement().asString().contains("payableAfterPoints").contains("노트");
        assertThat(BlueprintService.problems(changed, false)).anyMatch(p -> p.contains("근거가 예면"));
        // 근거가 사실(정산 기록)인 사례는 입력이 달라도 된다
        assertThat(DevBlueprintService.exampleBasisCases(dev)).noneMatch(p -> p.contains("pointsFollowSettlementRecord"));
    }

    @Test
    @DisplayName("g13 Q2: 설명에 요구사항과 똑같은 예 줄이 있으면 뺀다(띄어쓰기·문장부호 무시). 다른 문장과 빈 문단은 건드리지 않는다")
    void descriptionDropsRepeatedExamples() {
        List<String> req = List.of("addItem(\"노트\", 10000, 2) 뒤 payable(500) → 19500", "addItem(\"펜\", 3000, 1) 뒤 points() → 30");
        List<String> desc = List.of("쇼핑몰 주문 화면에 결제액 조회를 더한다.\naddItem(\"노트\", 10000, 2) 뒤  payable(500) → 19500",
                "addItem(\"펜\", 3000, 1) 뒤 points() → 30", "적립금 사용 정책을 정하고 이유를 설명하세요.");
        assertThat(TaskWritingService.withoutRequirementLines(desc, req)).containsExactly("쇼핑몰 주문 화면에 결제액 조회를 더한다.", "적립금 사용 정책을 정하고 이유를 설명하세요.");
        assertThat(TaskWritingService.withoutRequirementLines(desc, List.of())).isEqualTo(desc);
        assertThat(TaskPromptService.WRITING_DEV).contains("Examples appear only here, never again in description");
    }

    @Test
    @DisplayName("g12 DEV 프롬프트 충돌 정리: 정답 쪽은 사례 입력을 그대로 쓰고, 규모 문장에 '버그 수정 단계'·'숨은 테스트 1~2개'가 없고, DEV 헷갈리는 자료는 다른 계산이다(BIZ 는 그대로)")
    void devPromptConflictsRemoved() {
        assertThat(TaskPromptService.answerDev(40)).contains("situation input values exactly").doesNotContain("outside the visible examples");
        for (int m : new int[] {20, 40, 60}) {
            assertThat(TaskPromptService.stageDepth(false, m, true)).doesNotContain("bug fix").doesNotContain("1–2 hidden tests").contains("추가 method");
        }
        String dev = TaskPromptService.devBlueprint(40, TaskPromptService.stageDepth(false, 40, true), true);
        String biz = TaskPromptService.bizBlueprint(40, TaskPromptService.stageDepth(true, 40, true), true);
        assertThat(dev).contains("a DIFFERENT calculation or feature").doesNotContain("an old version doing the same calculation").doesNotContain("LegacyPointRule");
        assertThat(biz).contains("an old version doing the same calculation").doesNotContain("a DIFFERENT calculation or feature");
        assertThat(dev.split("AI default solution =", -1)).hasSize(2); // 기본 풀이 규칙은 한 번만
        assertThat(dev).doesNotContain("\n            - Never put generator"); // 텍스트 블록 들여쓰기가 맞으면 앞 공백이 없다
        assertThat(TaskPromptService.filler(false)).contains("Implement only what the one-line content says");
        // g12 03: 추가 메서드 예도 결함이 바꾸는 값을 보이면 안 된다(설계도 규칙·체크리스트·조기 점검)
        assertThat(dev).contains("they too use only inputs where the flaw does not change the result").contains("No example (기존 or 추가 method) has an expected value that the flaw changes")
                .doesNotContain("Examples calling a 추가 method are exempt");
        assertThat(TaskPromptService.check("blueprint-leak")).contains("예가 결함의 답을 보임");
    }

    @Test
    @DisplayName("점검 #7: BIZ 사실의 날짜·쪽 숫자는 숫자표에 없어도 되고, 값 숫자는 여전히 숫자표에 있어야 한다")
    void bizFactDatesAreNotFigures() throws Exception {
        BlueprintDto biz = parse(TaskPromptService.BIZ_EXAMPLE, true);
        var facts = new java.util.ArrayList<>(biz.facts());
        facts.add(new BlueprintDto.Fact("7월 3일 회의록 2쪽에 상담 일정이 적혀 있다.", "자료/운영 메모.pdf"));
        var withDate = withFacts(biz, facts);
        assertThat(BizNumbersService.blueprintProblems(withDate)).isEmpty();
        facts.add(new BlueprintDto.Fact("상담팀은 7월 대기 건수를 333건으로 기록했다.", "자료/운영 메모.pdf"));
        assertThat(BizNumbersService.blueprintProblems(withFacts(biz, facts))).anyMatch(p -> p.contains("333"));
    }

    private static BlueprintDto withFacts(BlueprintDto b, List<BlueprintDto.Fact> facts) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), facts, b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases(), b.neededFiles(), b.fillers());
    }
}
