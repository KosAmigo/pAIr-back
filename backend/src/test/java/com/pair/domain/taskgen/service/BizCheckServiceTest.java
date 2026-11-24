package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import static com.pair.domain.taskgen.service.BizFixtures.CSV;
import static com.pair.domain.taskgen.service.BizFixtures.DOC1;
import static com.pair.domain.taskgen.service.BizFixtures.DOC2;
import static com.pair.domain.taskgen.service.BizFixtures.edit;
import static com.pair.domain.taskgen.service.BizFixtures.replaceQuestion;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.infra.ai.AiUnavailableException;

/** 일부러 망가뜨린 Draft 에서 해당 검사만 실패하는지 (실제 PdfService, 가짜 풀이 모델) */
@Tag("slow")
class BizCheckServiceTest {

    private final PdfService pdf = new PdfService();
    private final BizFixtures.FakeSolver solver = new BizFixtures.FakeSolver();

    private List<GenCheckResultDto> run(BizDraftDto d) {
        return BizCheckService.forDraft(pdf, solver, "claude-sonnet-5-5", d).stream().map(c -> c.run(d)).toList();
    }

    private List<String> failed(BizDraftDto d) {
        return run(d).stream().filter(r -> !r.ok()).map(GenCheckResultDto::name).toList();
    }

    @Test
    @DisplayName("정상 Draft 는 검사 16개를 모두 통과하고, 풀이 모델에는 선택한 모델 이름과 문서 글이 간다")
    void allPass() {
        var results = run(BizFixtures.good("c"));
        assertThat(results).hasSize(16).allMatch(GenCheckResultDto::ok, results.toString());
        assertThat(solver.lastModel).isEqualTo("claude-sonnet-5-5");
        assertThat(solver.lastPrompt).contains("자료/설문 요약.pdf (2쪽)").contains("34%가 라이트").contains("1) 34%").contains("월,요금제,가입자 수");
        assertThat(solver.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("E2: 판단 지점이 비었거나 선택지가 1개거나 근거 문장이 응시자 자료에 없으면 '판단 지점과 근거가 자료에 있음' 만 실패한다")
    void judgmentPoints() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(failed(edit(d, b -> b.judgmentPoints(List.of())))).containsExactly(BizCheckService.JUDGMENT);
        var one = new DevDraftDto.JudgmentPoint("정할 것", List.of("한 가지"), "응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.");
        assertThat(failed(edit(d, b -> b.judgmentPoints(List.of(one))))).containsExactly(BizCheckService.JUDGMENT);
        var missing = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), "이 문장은 자료 어디에도 없습니다 절대로");
        var r = result(edit(d, b -> b.judgmentPoints(List.of(missing))), BizCheckService.JUDGMENT);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.detail()).contains("근거 문장을 응시자 자료에서 찾지 못했습니다");
        // 체크리스트(숨은 채점 기준)에만 있는 문장은 응시자에게 안 보이므로 근거가 될 수 없다
        var hiddenOnly = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), "결론을 밝힌다.");
        assertThat(failed(edit(d, b -> b.judgmentPoints(List.of(hiddenOnly))))).containsExactly(BizCheckService.JUDGMENT);
    }

    @Test
    @DisplayName("E3: 숨은 결함이 비었거나, 어느 문항·결론인지 없거나, 놓쳐도 답·결론이 같으면 '숨은 결함이 답·결론을 가름' 만 실패한다")
    void hiddenFlaws() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(failed(edit(d, b -> b.hiddenFlaws(List.of())))).containsExactly(BizCheckService.HIDDEN_FLAW);
        var noRef = new DevDraftDto.HiddenFlaw("결함", "문서 대조", "문항 번호 없음");
        assertThat(failed(edit(d, b -> b.hiddenFlaws(List.of(noRef))))).containsExactly(BizCheckService.HIDDEN_FLAW);
        var outOfRange = new DevDraftDto.HiddenFlaw("결함", "문서 대조", "Q9");
        assertThat(failed(edit(d, b -> b.hiddenFlaws(List.of(outOfRange))))).containsExactly(BizCheckService.HIDDEN_FLAW);
        var noVerify = new DevDraftDto.HiddenFlaw("결함", "", "Q3");
        assertThat(failed(edit(d, b -> b.hiddenFlaws(List.of(noVerify))))).containsExactly(BizCheckService.HIDDEN_FLAW);
        // 문항 번호나 결론으로 가리키고 놓치면 답이 달라지면 통과(단답형 Q3 은 흔한 오답이 있음)
        var byQuestion = new DevDraftDto.HiddenFlaw("결함", "문서 대조", "문항 3");
        assertThat(failed(edit(d, b -> b.hiddenFlaws(List.of(byQuestion))))).isEmpty();
        // 결론을 가리키는데 함정 결론이 정답 결론과 같으면 결과가 달라지지 않는다 → 이 검사와 '함정이 결론을 바꿈' 이 함께 실패
        var sameConclusion = new BizDraftDto.Trap("t", BizFixtures.EVIDENCE, "시범 출시", "시범 출시");
        var onlyConclusion = new DevDraftDto.HiddenFlaw("결함", "문서 대조", "결론");
        assertThat(failed(edit(d, b -> b.trap(sameConclusion).hiddenFlaws(List.of(onlyConclusion)))))
                .containsExactly(BizCheckService.CONCLUSION, BizCheckService.HIDDEN_FLAW);
    }

    @Test
    @DisplayName("옛 형식(judgmentPoints·hiddenFlaws 필드 없음)은 읽히고, 두 검사가 warn 으로만 알린다(다시 생성하지 않음)")
    void legacyWithoutE2E3() {
        BizDraftDto legacy = edit(BizFixtures.good("c"), b -> b.judgmentPoints(null).hiddenFlaws(null));
        var notOk = run(legacy).stream().filter(r -> !r.ok()).toList();
        assertThat(notOk).extracting(GenCheckResultDto::name).containsExactly(BizCheckService.JUDGMENT, BizCheckService.HIDDEN_FLAW);
        assertThat(notOk).allSatisfy(r -> {
            assertThat(r.level()).isEqualTo(GenCheckResultDto.WARN);
            assertThat(r.needsRegeneration()).isFalse();
        });
    }

    @Test
    @DisplayName("문항의 근거 문서가 1개뿐이면 '근거 문서 2개 이상' 만 실패한다")
    void oneSource() {
        var q = BizFixtures.single("현재 이용자 중 라이트 전환 의향 비율은?", List.of("34%", "38%", "41%", "50%"), 1, DOC1 + "|2");
        BizDraftDto d = BizFixtures.good("c");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 0, q))))).containsExactly(BizCheckService.SOURCES);
    }

    @Test
    @DisplayName("같은 문서를 두 번 적거나 없는 쪽·없는 경로를 적어도 '근거 문서 2개 이상' 만 실패한다")
    void badSources() {
        BizDraftDto d = BizFixtures.good("c");
        var same = BizFixtures.single("q", List.of("a", "b", "c", "d"), 1, DOC1 + "|1", DOC1 + "|2");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 0, same))))).containsExactly(BizCheckService.SOURCES);
        var noPage = BizFixtures.single("q", List.of("a", "b", "c", "d"), 1, DOC1 + "|3", DOC2 + "|1");
        assertThat(run(edit(d, b -> b.questions(replaceQuestion(d, 0, noPage)))).stream().filter(r -> !r.ok()).map(GenCheckResultDto::detail).toList())
                .singleElement().asString().contains("없는 쪽");
        var noDoc = BizFixtures.single("q", List.of("a", "b", "c", "d"), 1, "자료/없음.pdf|1", DOC2 + "|1");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 0, noDoc))))).containsExactly(BizCheckService.SOURCES);
    }

    @Test
    @DisplayName("함정 근거 문장이 자료에 없으면 '함정 근거' 만 실패하고, 공백 차이는 무시한다")
    void trapEvidence() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(failed(edit(d, b -> b.trap(new BizDraftDto.Trap("함정", "자료 어디에도 없는 문장입니다 정말로 없어요.", "A안", "B안"))))).containsExactly(BizCheckService.TRAP);
        assertThat(failed(edit(d, b -> b.trap(new BizDraftDto.Trap("함정", "현재  이용자 설문에서는\n34%가 라이트로 옮길 의향이 있다고 답했습니다.", "A안", "B안"))))).isEmpty();
    }

    @Test
    @DisplayName("설명·요구사항에 단답형 정답 수치가 있으면 '정답 노출 없음' 만 실패한다 ('60분 동안' 은 봐준다)")
    void answerLeak() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(failed(edit(d, b -> b.description(List.of("가입자는 21080명이 늘었습니다."))))).containsExactly(BizCheckService.LEAK);
        assertThat(failed(edit(d, b -> b.description(List.of("60분 동안 자료를 분석합니다. 2000000원 규모입니다."))))).isEmpty();
    }

    @Test
    @DisplayName("CSV 의 행마다 칸 수가 다르면 'CSV 형식' 만 실패한다")
    void csvColumns() {
        BizDraftDto d = BizFixtures.good("c");
        var csv = List.of(new DevDraftDto.FileDraft(CSV, "월,요금제,가입자 수,월 요금(원)\n2026-10,베이직,62000,9900\n2026-10,라이트,0,5900\n2026-09,베이직,60000,9900\n2026-08,베이직,58000\n"));
        assertThat(failed(edit(d, b -> b.csvFiles(csv)))).containsExactly(BizCheckService.CSV);
    }

    @Test
    @DisplayName("계산식 결과가 인정 답안과 다르거나 식이 허용 밖이면 '단답형 검산' 만 실패한다")
    void calcWrong() {
        BizDraftDto d = BizFixtures.good("c");
        var off = BizFixtures.shortQ("q", "20000", "round(v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\") * 0.34)", CSV + "|", DOC1 + "|2");
        solver.reply = "{\"answers\": [\"1\", \"2\", \"20000\", \"2000\"]}";
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, off))))).containsExactly(BizCheckService.CALC);
        solver.reply = "{\"answers\": [\"1\", \"2\", \"21080\", \"2000\"]}";
        var bad = BizFixtures.shortQ("q", "21080", "sqrt(4)", CSV + "|", DOC1 + "|2");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, bad))))).containsExactly(BizCheckService.CALC);
        // 0.5% 이내 차이는 같은 값으로 본다
        var near = BizFixtures.shortQ("q", "21100", "round(v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\") * 0.34)", CSV + "|", DOC1 + "|2");
        solver.reply = "{\"answers\": [\"1\", \"2\", \"21080\", \"2000\"]}";
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, near))))).isEmpty();
    }

    @Test
    @DisplayName("풀이 모델이 틀리게 답하거나 호출이 실패하면 '다른 회사 모델이 풀기' 만 실패한다")
    void solverWrong() {
        BizDraftDto d = BizFixtures.good("c");
        solver.reply = "{\"answers\": [\"2\", \"2\", \"21080\", \"2000\"]}";
        var r = run(d);
        assertThat(r.stream().filter(x -> !x.ok()).map(GenCheckResultDto::name)).containsExactly(BizCheckService.SOLVE);
        assertThat(r.get(6).detail()).contains("Q1");
        solver.reply = "답을 모르겠습니다";
        assertThat(failed(d)).containsExactly(BizCheckService.SOLVE);
        solver.error = new AiUnavailableException("Claude 요청 한도 (429)");
        assertThat(run(d).get(6).detail()).contains("429");
    }

    @Test
    @DisplayName("형식이 틀리면 형식만 보고 나머지는 확인하지 않는다 (풀이 모델도 부르지 않는다)")
    void formatFails() {
        BizDraftDto d = BizFixtures.good("c");
        var oneDoc = edit(d, b -> b.documents(List.of(d.documents().get(0))));
        var r = run(oneDoc);
        assertThat(r.get(0).ok()).isFalse();
        assertThat(r.get(0).detail()).contains("2개 이상");
        assertThat(r.subList(1, 10)).noneMatch(GenCheckResultDto::ok);
        assertThat(solver.calls.get()).isZero();

        var badPath = edit(d, b -> b.csvFiles(List.of(new DevDraftDto.FileDraft("자료/../evil.csv", "a,b\n1,2\n"))));
        assertThat(run(badPath).get(0).detail()).contains("경로 형식");
    }

    @Test
    @DisplayName("20분 과제는 객관식 2 + 단답형 1 을 허용하고, 40분·60분은 단답형 2개가 필요하다")
    void twentyMinuteQuestionCounts() {
        BizDraftDto d = BizFixtures.good("c");
        BizDraftDto small = edit(d, b -> b.questions(d.questions().subList(0, 3)));
        assertThat(BizCheckService.formatProblem(small, pdf, 20)).isNull();
        assertThat(BizCheckService.formatProblem(small, pdf, 40)).contains("단답형이 2개");
        assertThat(BizCheckService.formatProblem(small, pdf, 60)).contains("단답형이 2개");
        BizDraftDto noShort = edit(d, b -> b.questions(d.questions().subList(0, 2)));
        assertThat(BizCheckService.formatProblem(noShort, pdf, 20)).contains("단답형이 1개");
        assertThat(BizCheckService.forDraft(pdf, solver, "m", small, 20).stream().map(c -> c.run(small)).filter(r -> !r.ok()).toList()).isEmpty();
    }

    @Test
    @DisplayName("객관식 보기·정답 수, 단답형 개수, 후속 질문 수, 체크리스트 '필수 항목' 을 확인한다")
    void formatRules() {
        BizDraftDto d = BizFixtures.good("c");
        var threeChoices = BizFixtures.single("q", List.of("a", "b", "c"), 1, DOC1 + "|1", DOC2 + "|1");
        assertThat(BizCheckService.formatProblem(edit(d, b -> b.questions(replaceQuestion(d, 0, threeChoices))), pdf)).contains("보기가 4개");
        var noCorrect = BizFixtures.single("q", List.of("a", "b", "c", "d"), 9, DOC1 + "|1", DOC2 + "|1");
        assertThat(BizCheckService.formatProblem(edit(d, b -> b.questions(replaceQuestion(d, 0, noCorrect))), pdf)).contains("정답 보기가 1개");
        assertThat(BizCheckService.formatProblem(edit(d, b -> b.questions(d.questions().subList(0, 3))), pdf)).contains("단답형이 2개");
        BizDraftDto noList = new BizDraftDto(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(),
                List.of("하나"), d.documents(), d.csvFiles(), d.checklist(), d.trap(), d.questions());
        assertThat(BizCheckService.formatProblem(noList, pdf)).contains("후속 질문");
        BizDraftDto noHeading = new BizDraftDto(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(),
                d.followUps(), d.documents(), d.csvFiles(), "# 체크리스트", d.trap(), d.questions());
        assertThat(BizCheckService.formatProblem(noHeading, pdf)).contains("필수 항목");
    }

    @Test
    @DisplayName("한 쪽 글이 너무 길어 PDF 쪽이 늘거나, 글꼴에 없는 글자(이모지)가 있으면 형식이 실패한다")
    void pdfProblems() {
        BizDraftDto d = BizFixtures.good("c");
        String longPage = "가나다라마바사 아자차카타파하 ".repeat(40).replace(" ", "\n").repeat(3);
        var tooLong = edit(d, b -> b.documents(List.of(new BizDraftDto.DocDraft(DOC1, List.of(longPage)), d.documents().get(1))));
        assertThat(BizCheckService.formatProblem(tooLong, pdf)).contains("쪽이 늘어납니다");
        var emoji = edit(d, b -> b.documents(List.of(new BizDraftDto.DocDraft(DOC1, List.of("좋아요 😀")), d.documents().get(1))));
        assertThat(BizCheckService.formatProblem(emoji, pdf)).contains("PDF 로 만들 수 없습니다");
    }

    // ── 검수 결과로 더한 검사 3개 ─────────────────────

    private GenCheckResultDto result(BizDraftDto d, String name) {
        return run(d).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("M5: 지표 정의가 값을 합치는 방법(차이, 비율의 금액)을 말하면 '정의·문항에 계산 절차 없음' 만 실패한다")
    void noProcedure() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(result(d, BizCheckService.NO_PROCEDURE).ok()).isTrue();
        // 리뷰에서 나온 문장: 가격 효과를 증가율의 차이로 정의함
        var diff = edit(d, b -> b.description(List.of("가격 효과는 인상 구역 매출 증가율과 비교 구역 매출 증가율의 차이를 뜻하며 자료를 봅니다.")));
        assertThat(failed(diff)).containsExactly(BizCheckService.NO_PROCEDURE);
        var share = edit(d, b -> b.requirements(List.of("기준 기간 매출액 중 가격 효과의 비율에 해당하는 금액을 구한다.")));
        assertThat(failed(share)).containsExactly(BizCheckService.NO_PROCEDURE);
        // 무엇을 세는지만 쓴 정의는 통과
        var count = edit(d, b -> b.description(List.of("가격 효과는 가격 인상이 매출에 미친 영향을 가리킵니다.")));
        assertThat(result(count, BizCheckService.NO_PROCEDURE).ok()).isTrue();
    }

    @Test
    @DisplayName("M5: v5 07의 정의 문장(계산 방법)과 차이 문장은 '정의·문항에 계산 절차 없음' 만 실패한다")
    void noProcedureDefinitionAndDifference() {
        BizDraftDto d = BizFixtures.good("c");
        var def = edit(d, b -> b.description(List.of("여기서 전분기 대비 증가율은 직전 분기의 만족도 점유율을 기준으로 본 해당 분기 점유율의 상대적 증가 정도를 뜻한다.")));
        assertThat(failed(def)).containsExactly(BizCheckService.NO_PROCEDURE);
        var diff = edit(d, b -> b.description(List.of("점유율과 증가율은 모두 퍼센트로 표현하지만 가리키는 대상은 서로 다르다.")));
        assertThat(failed(diff)).containsExactly(BizCheckService.NO_PROCEDURE);
        var diff2 = edit(d, b -> b.requirements(List.of("방문 고객과 등록 고객은 다르다.")));
        assertThat(failed(diff2)).containsExactly(BizCheckService.NO_PROCEDURE);
        var cause = edit(d, b -> b.requirements(List.of("원인: 집계 기간이 달랐다.")));
        assertThat(failed(cause)).containsExactly(BizCheckService.NO_PROCEDURE);
        var must = edit(d, b -> b.requirements(List.of("반품률은 판매 건수로 가중해서 계산해야 한다.")));
        assertThat(failed(must)).containsExactly(BizCheckService.NO_PROCEDURE);
        // 결과물 쓰기 안내와 무엇을 세는지만 쓴 정의는 통과
        var ok = edit(d, b -> b.requirements(List.of("결론과 근거 3개를 기획안에 담아 제출해야 한다.", "증가율은 분기별 만족도 점유율의 변화를 가리킨다.")));
        assertThat(result(ok, BizCheckService.NO_PROCEDURE).ok()).isTrue();
    }

    @Test
    @DisplayName("M5 오탐 방지(v7 04): 자료 문서 속 판단 지점 근거 사실('~확보해야 한다')은 통과하고, 설명·문항의 지시 말투는 계속 실패한다")
    void noProcedureFactInDocument() {
        BizDraftDto d = BizFixtures.good("c");
        String fact = "일괄 납품은 검수 인력을 하루만 배치하지만 보관 공간을 한 번에 넓게 확보해야 한다.";
        var docs = new ArrayList<>(d.documents());
        var first = docs.get(0);
        var pages = new ArrayList<>(first.pages());
        pages.set(0, pages.get(0) + "\n" + fact);
        docs.set(0, new BizDraftDto.DocDraft(first.path(), pages));
        assertThat(result(edit(d, b -> b.documents(docs)), BizCheckService.NO_PROCEDURE).ok()).isTrue();
        var must = edit(d, b -> b.requirements(List.of(fact)));
        assertThat(failed(must)).containsExactly(BizCheckService.NO_PROCEDURE);
    }

    @Test
    @DisplayName("M4 오탐 방지(v7 06): 근거 한 줄에 선택지 둘의 근거가 함께 있으면 통과하고, 한 선택지의 근거가 없으면 실패한다")
    void judgmentEvidenceOneLine() {
        BizDraftDto d = BizFixtures.good("c");
        String basis = "응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.";
        var both = new DevDraftDto.JudgmentPoint("정할 것", List.of("시범 출시", "전면 출시"), basis, List.of(basis));
        assertThat(result(edit(d, b -> b.judgmentPoints(List.of(both))), BizCheckService.JUDGMENT).ok()).isFalse(); // 선택지 말이 근거에 없음
        String line = "응답자의 38%가 시범 출시를 원했고 전면 출시는 12%였습니다.";
        String shown = "응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.";
        var docs = new ArrayList<>(d.documents());
        var f = docs.get(0);
        var pg = new ArrayList<>(f.pages());
        pg.set(0, pg.get(0) + "\n" + line);
        docs.set(0, new BizDraftDto.DocDraft(f.path(), pg));
        var ok = new DevDraftDto.JudgmentPoint("정할 것", List.of("시범 출시", "전면 출시"), shown, List.of(line));
        assertThat(result(edit(d, b -> b.documents(docs).judgmentPoints(List.of(ok))), BizCheckService.JUDGMENT).ok()).isTrue();
    }

    @Test
    @DisplayName("M5: v5 에서 위반 없다고 검수한 과제(01·03·04·05·09)의 응시자 글은 통과한다")
    void noProcedurePassesCleanV5Tasks() throws Exception {
        for (String name : List.of("01-monthly-period", "03-score-ledger", "04-option-summary", "05-regional-returns", "09-shipment-review")) {
            String text;
            try (var in = getClass().getResourceAsStream("/task-fixtures/m5-clean/" + name + ".txt")) {
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var d = edit(BizFixtures.good("c"), b -> b.description(text.lines().filter(l -> !l.isBlank()).toList()));
            assertThat(result(d, BizCheckService.NO_PROCEDURE).detail()).as(name).startsWith("정의·문항에 값을 합치는");
        }
    }

    @Test
    @DisplayName("M5: 자료 글의 규칙 문장·원인 설명은 '함정 근거가 사실 진술임' warn 으로 알린다")
    void ruleSentenceWarn() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(result(d, BizCheckService.EVIDENCE_STYLE).ok()).isTrue();
        var rule = edit(d, b -> b.documents(List.of(new BizDraftDto.DocDraft(DOC1, List.of("이탈 고객 설문 결과입니다. 한 구간의 남는 처리 여력은 다른 구간의 실적으로 잡히지 않는다.",
                "현재 이용자 설문입니다.\n" + BizFixtures.EVIDENCE)), d.documents().get(1))));
        var r = result(rule, BizCheckService.EVIDENCE_STYLE);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.WARN);
        assertThat(r.detail()).contains("규칙 문장");
        var cause = edit(d, b -> b.description(List.of("광고를 보지 않았어도 구매했을 고객이 있어 서로 다른 관측값입니다.")));
        assertThat(result(cause, BizCheckService.EVIDENCE_STYLE).level()).isEqualTo(GenCheckResultDto.WARN);
    }

    @Test
    @DisplayName("E3 M3: 함정 문항이 칸 하나를 읽기만 하거나 다른 문항과 같은 계산이면 '함정 문항이 자료 대조를 요구함' 만 실패한다")
    void trapQuestions() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(result(d, BizCheckService.TRAP_QUESTION).ok()).isTrue();
        var q3 = d.questions().get(2);
        var lookup = new BizDraftDto.QuestionDraft(q3.type(), q3.content(), null, List.of("62000"),
                "round(v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\"), 0)", q3.sources(), q3.commonWrongAnswers());
        var simple = edit(d, b -> b.questions(replaceQuestion(d, 2, lookup)));
        var r = result(simple, BizCheckService.TRAP_QUESTION);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.detail()).contains("Q3").contains("칸 하나");
        // 같은 계산을 다른 문구로 되풀이
        var q4 = d.questions().get(3);
        var same = new BizDraftDto.QuestionDraft(q4.type(), q4.content(), null, q3.accepted(), q3.calc(), q4.sources(), q4.commonWrongAnswers());
        var repeated = edit(d, b -> b.questions(replaceQuestion(d, 3, same)));
        assertThat(result(repeated, BizCheckService.TRAP_QUESTION).detail()).contains("Q3 와 Q4 가 같은 계산");
        // 결론 객관식의 정답 보기가 자료 문장 그대로면 단순 조회
        var q2 = d.questions().get(1);
        var copied = new BizDraftDto.QuestionDraft(q2.type(), q2.content(), List.of(new BizDraftDto.Choice("응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.", true),
                new BizDraftDto.Choice("가", false), new BizDraftDto.Choice("나", false), new BizDraftDto.Choice("다", false)), null, null, q2.sources());
        var flaws = List.of(new DevDraftDto.HiddenFlaw("결함", "문서 대조", "Q2, Q3"));
        var lookupSingle = edit(d, b -> b.questions(replaceQuestion(d, 1, copied)).hiddenFlaws(flaws));
        assertThat(result(lookupSingle, BizCheckService.TRAP_QUESTION).detail()).contains("Q2 의 정답 보기가 자료 문장 그대로");
    }

    @Test
    @DisplayName("핵심어가 설명·요구사항·자료 파일 이름에 있으면 '함정 핵심어 노출 없음' 만 실패한다 (공백 무시, 자료 본문은 보지 않음)")
    void trapWordExposed() {
        BizDraftDto d = BizFixtures.good("c");
        var hit = edit(d, b -> b.description(List.of("의향  보정 없이 계산하면 틀립니다.")));
        assertThat(failed(hit)).containsExactly(BizCheckService.TRAP_WORDS);
        assertThat(result(hit, BizCheckService.TRAP_WORDS).detail()).contains("설명").contains("의향 보정");
        var inReq = edit(d, b -> b.requirements(List.of("전환율을 반드시 쓴다.")));
        assertThat(result(inReq, BizCheckService.TRAP_WORDS).detail()).contains("요구사항").contains("전환율");
        // 자료 본문에 있는 말은 괜찮다 (함정 근거 문장이 거기 있어야 하므로). 설문 요약 문서에 "의향" 이 있어도 통과한다
        assertThat(failed(d)).isEmpty();
        assertThat(failed(edit(d, b -> b.trapKeywords(List.of("전환율"))))).containsExactly(BizCheckService.TRAP_WORDS);
    }

    @Test
    @DisplayName("설명·요구사항에서 자료의 문서 이름을 언급한 것은 핵심어 노출이 아니다 (06 비교표 오탐), 이름 밖의 핵심어는 그대로 걸린다")
    void trapWordInDocumentName() {
        BizDraftDto base = BizFixtures.good("c");
        var docs = new ArrayList<>(base.documents());
        docs.set(0, new BizDraftDto.DocDraft(DOC1, List.of("# 시범 비교표\n이탈 고객 설문 결과입니다. 응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.")));
        BizDraftDto d = edit(base, b -> b.documents(docs).trapKeywords(List.of("비교표", "보정")));
        var named = edit(d, b -> b.description(List.of("구매팀이 작성한 비교표를 살펴봅니다.")));
        assertThat(result(named, BizCheckService.TRAP_WORDS).ok()).isTrue();
        var both = edit(d, b -> b.description(List.of("비교표를 보고 보정 없이 계산합니다.")));
        assertThat(result(both, BizCheckService.TRAP_WORDS).detail()).contains("보정").doesNotContain("비교표");
    }

    @Test
    @DisplayName("단답형의 흔한 오답이 인정 답안과 같거나 0.5% 안이면 '단답 오답이 인정 답안과 다름' 만 실패한다")
    void commonWrongAnswers() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(result(d, BizCheckService.WRONG_ANSWERS).ok()).isTrue();

        var q = d.questions().get(2); // 인정 답안 21080
        var same = BizFixtures.wrong(BizFixtures.shortQ(q.content(), "21080", q.calc(), CSV + "|", DOC1 + "|2"), "21080", "같은 값");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, same))))).containsExactly(BizCheckService.WRONG_ANSWERS);
        assertThat(result(edit(d, b -> b.questions(replaceQuestion(d, 2, same))), BizCheckService.WRONG_ANSWERS).detail()).contains("21080").contains("인정 답안과 같아");

        var near = BizFixtures.wrong(BizFixtures.shortQ(q.content(), "21080", q.calc(), CSV + "|", DOC1 + "|2"), "21100", "0.1% 차이");
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, near))))).containsExactly(BizCheckService.WRONG_ANSWERS);
        var far = BizFixtures.wrong(BizFixtures.shortQ(q.content(), "21080", q.calc(), CSV + "|", DOC1 + "|2"), "21300", "1% 차이");
        // 기본 오답 "1" 과 "21300" 모두 인정 답안과 다르므로 통과
        assertThat(failed(edit(d, b -> b.questions(replaceQuestion(d, 2, far))))).isEmpty();

        // 흔한 오답이 아예 없거나 숫자가 아니어도 실패
        var none = new BizDraftDto.QuestionDraft(q.type(), q.content(), q.choices(), q.accepted(), q.calc(), q.sources(), List.of());
        assertThat(result(edit(d, b -> b.questions(replaceQuestion(d, 2, none))), BizCheckService.WRONG_ANSWERS).detail()).contains("없습니다");
        var text = BizFixtures.wrong(none, "많음", "숫자 아님");
        assertThat(result(edit(d, b -> b.questions(replaceQuestion(d, 2, text))), BizCheckService.WRONG_ANSWERS).detail()).contains("숫자가 아닙니다");
    }

    @Test
    @DisplayName("3번째 후속 질문에 핵심어가 있거나 앞의 공용 질문과 같으면 '후속 질문이 과제에 맞음' 만 실패한다")
    void followUp() {
        BizDraftDto d = BizFixtures.good("c");
        assertThat(result(d, BizCheckService.FOLLOW_UP).ok()).isTrue();
        var keyword = edit(d, b -> b.followUps(List.of("직접 확인한 것은?", "채택하지 않은 제안은?", "전환율을 어떻게 검산했나요?")));
        assertThat(failed(keyword)).containsExactly(BizCheckService.FOLLOW_UP);
        assertThat(result(keyword, BizCheckService.FOLLOW_UP).detail()).contains("전환율");
        var same = edit(d, b -> b.followUps(List.of("직접 확인한 것은?", "채택하지 않은 제안은?", "채택하지 않은 제안은?")));
        assertThat(failed(same)).containsExactly(BizCheckService.FOLLOW_UP);
    }

    @Test
    @DisplayName("함정 근거가 '~만으로 판단하지 않는다' 같은 지시문이면 경고(warn)만 하고 다시 생성하지는 않는다")
    void evidenceDirective() {
        var r = BizCheckService.evidenceStyle(edit(BizFixtures.good("c"), x -> x.trap(new BizDraftDto.Trap("함정",
                "이 값만으로 하루 인력 충족 여부를 판단하지 않는다."))));
        assertThat(r.ok()).isFalse();
        assertThat(r.needsRegeneration()).isFalse();
        for (String ev : List.of("두 값을 더해서는 안 된다는 점", "혼동하지 말 것")) {
            assertThat(BizCheckService.evidenceStyle(edit(BizFixtures.good("c"), b -> b.trap(new BizDraftDto.Trap("함정", ev))))
                    .ok()).isFalse();
        }
        assertThat(BizCheckService.evidenceStyle(BizFixtures.good("c")).ok()).isTrue();
    }

    @Test
    @DisplayName("함정에 빠진 결론이 정답 결론과 같으면 fail(다시 생성), 다르면 통과, 필드가 없으면 warn")
    void conclusionDiffers() {
        var same = BizCheckService.conclusion(edit(BizFixtures.good("c"), b -> b.trap(new BizDraftDto.Trap("함정", "근거 문장입니다 충분히 깁니다", "보류한다.", "보류 한다"))));
        assertThat(same.ok()).isFalse();
        assertThat(same.needsRegeneration()).isTrue();
        var missing = BizCheckService.conclusion(edit(BizFixtures.good("c"), b -> b.trap(new BizDraftDto.Trap("함정", "근거 문장입니다 충분히 깁니다"))));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.needsRegeneration()).isFalse();
        assertThat(BizCheckService.conclusion(BizFixtures.good("c")).ok()).isTrue();
        assertThat(failed(edit(BizFixtures.good("c"), b -> b.trap(new BizDraftDto.Trap("함정", BizFixtures.EVIDENCE, "A안", "A안"))))).containsExactly(BizCheckService.CONCLUSION);
    }
}
