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
    @DisplayName("정상 Draft 는 검사 12개를 모두 통과하고, 풀이 모델에는 선택한 모델 이름과 문서 글이 간다")
    void allPass() {
        var results = run(BizFixtures.good("c"));
        assertThat(results).hasSize(12).allMatch(GenCheckResultDto::ok, results.toString());
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
