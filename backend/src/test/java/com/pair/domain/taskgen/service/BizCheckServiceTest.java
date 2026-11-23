package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import static com.pair.domain.taskgen.service.BizFixtures.CSV;
import static com.pair.domain.taskgen.service.BizFixtures.DOC1;
import static com.pair.domain.taskgen.service.BizFixtures.DOC2;
import static com.pair.domain.taskgen.service.BizFixtures.edit;
import static com.pair.domain.taskgen.service.BizFixtures.replaceQuestion;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.infra.ai.AiUnavailableException;

/** 일부러 망가뜨린 Draft 에서 해당 검사만 실패하는지 (실제 PdfService, 가짜 풀이 모델) */
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
    @DisplayName("정상 Draft 는 검사 7개를 모두 통과하고, 풀이 모델에는 선택한 모델 이름과 문서 글이 간다")
    void allPass() {
        var results = run(BizFixtures.good("c"));
        assertThat(results).hasSize(7).allMatch(GenCheckResultDto::ok, results.toString());
        assertThat(solver.lastModel).isEqualTo("claude-sonnet-5-5");
        assertThat(solver.lastPrompt).contains("자료/설문 요약.pdf (2쪽)").contains("34%가 라이트").contains("1) 34%").contains("월,요금제,가입자 수");
        assertThat(solver.calls.get()).isEqualTo(1);
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
        assertThat(failed(edit(d, b -> b.trap(new BizDraftDto.Trap("함정", "자료 어디에도 없는 문장입니다 정말로 없어요."))))).containsExactly(BizCheckService.TRAP);
        assertThat(failed(edit(d, b -> b.trap(new BizDraftDto.Trap("함정", "현재  이용자 설문에서는\n34%가 라이트로 옮길 의향이 있다고 답했습니다."))))).isEmpty();
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
        assertThat(r.subList(1, 7)).noneMatch(GenCheckResultDto::ok);
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
}
