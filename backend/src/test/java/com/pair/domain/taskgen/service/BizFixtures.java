package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.pair.domain.taskgen.dto.BizDraftDto.Choice;
import com.pair.domain.taskgen.dto.BizDraftDto.DocDraft;
import com.pair.domain.taskgen.dto.BizDraftDto.QuestionDraft;
import com.pair.domain.taskgen.dto.BizDraftDto.Source;
import com.pair.domain.taskgen.dto.BizDraftDto.Trap;
import com.pair.domain.taskgen.dto.BizDraftDto.Type;

/** 비개발 테스트용 Draft 와 가짜 풀이 모델 */
final class BizFixtures {

    static final String CSV = "자료/매출.csv";
    static final String DOC1 = "자료/설문 요약.pdf";
    static final String DOC2 = "자료/시장 보고.pdf";
    static final String EVIDENCE = "현재 이용자 설문에서는 34%가 라이트로 옮길 의향이 있다고 답했습니다.";

    private BizFixtures() {}

    static TaskGenRequest form() {
        return new TaskGenRequest("BIZ", "저가 요금제 출시 여부", "설문 의향을 실제 전환율로 보정하지 않음", "SELF", 60);
    }

    /** 가짜 풀이 모델이 맞히는 정상 Draft (정답: 1, 2, 21080, 2000) */
    static BizDraftDto good(String code) {
        return new BizDraftDto("저가 요금제 출시 검토", "저가 요금제를 내놓아도 될지 자료로 판단합니다.", code, "기획안 작성",
                List.of("영상 구독 서비스의 이탈률이 올랐습니다.", "자료를 보고 1페이지 기획안을 쓰세요."),
                List.of("결론과 근거 3개를 쓴다.", "리스크를 1개 이상 쓴다."),
                List.of("직접 확인한 것은?", "채택하지 않은 제안은?", "AI 가 계산한 인원을 자료의 어느 칸 값과 맞춰 봤나요?"),
                List.of(new DocDraft(DOC1, List.of("이탈 고객 설문 결과입니다. 응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.",
                                "현재 이용자 설문입니다.\n" + EVIDENCE)),
                        new DocDraft(DOC2, List.of("월 이탈률은 5월 4.1%에서 10월 6.8%로 올랐습니다."))),
                List.of(new DevDraftDto.FileDraft(CSV, "월,요금제,가입자 수,월 요금(원)\n2026-10,베이직,62000,9900\n2026-10,라이트,0,5900\n2026-09,베이직,60000,9900\n")),
                "# 체크리스트\n\n## 필수 항목\n1. 결론을 밝힌다.\n\n## 참고 계산\n62000 x 0.34",
                new Trap("설문 의향을 실제 전환율로 보정하지 않는다.", EVIDENCE, "라이트 요금제를 시범 출시한다", "라이트 요금제를 전면 출시한다"),
                List.of(single("현재 이용자 중 라이트 전환 의향 비율은?", List.of("34%", "38%", "41%", "50%"), 1, DOC1 + "|2", DOC2 + "|1"),
                        single("5월에서 10월까지 월 이탈률은 어떻게 변했나?", List.of("4.1%에서 5.5%", "4.1%에서 6.8%", "6.8%에서 4.1%", "변화 없음"), 2, DOC2 + "|1", DOC1 + "|1"),
                        wrong(shortQ("10월 베이직 가입자 중 라이트 전환 예상 인원은 몇 명인가요? (명, 소수 첫째 자리 반올림)", "21080",
                                "round(v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\") * 0.34)", CSV + "|", DOC1 + "|2"), "23560", "설문 의향 38%를 그대로 곱함"),
                        wrong(shortQ("9월 대비 10월 베이직 가입자는 몇 명 늘었나요? (명)", "2000",
                                "v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\") - v(\"자료/매출.csv\", \"월=2026-09;요금제=베이직\", \"가입자 수\")",
                                CSV + "|", DOC2 + "|1"), "62000", "증가분이 아니라 10월 가입자 수를 적음")),
                null, null, List.of("전환율", "의향 보정"), List.of(JUDGMENT), List.of(FLAW));
    }

    /** E2 판단 지점 예시: 근거 문장은 문서 1쪽 글에 그대로 있다 */
    static final DevDraftDto.JudgmentPoint JUDGMENT = new DevDraftDto.JudgmentPoint("라이트 요금제를 시범으로 낼지 전면으로 낼지",
            List.of("시범 출시", "전면 출시"), "응답자의 38%가 라이트 요금제 이용 의향을 밝혔습니다.");

    /** E3 숨은 결함 예시: 3번 단답형(전환 예상 인원)과 결론이 달라진다 */
    static final DevDraftDto.HiddenFlaw FLAW = new DevDraftDto.HiddenFlaw("이탈 고객 설문의 의향을 현재 이용자 전체의 전환율로 착각한다",
            "자료/설문 요약.pdf 1쪽과 2쪽의 설문 대상을 대조", "Q3, 결론");

    static QuestionDraft single(String content, List<String> choices, int correct, String... sources) {
        List<Choice> cs = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) cs.add(new Choice(choices.get(i), i + 1 == correct));
        return new QuestionDraft(Type.SINGLE, content, cs, List.of(), "", sources(sources));
    }

    static QuestionDraft shortQ(String content, String accepted, String calc, String... sources) {
        return new QuestionDraft(Type.SHORT, content, List.of(), List.of(accepted), calc, sources(sources), List.of(new BizDraftDto.WrongAnswer("1", "테스트용 흔한 오답")));
    }

    /** 흔한 오답 1개를 더한 문항 */
    static QuestionDraft wrong(QuestionDraft q, String value, String reason) {
        List<BizDraftDto.WrongAnswer> all = new ArrayList<>(q.commonWrongAnswers() == null ? List.of() : q.commonWrongAnswers());
        all.add(new BizDraftDto.WrongAnswer(value, reason));
        return new QuestionDraft(q.type(), q.content(), q.choices(), q.accepted(), q.calc(), q.sources(), all);
    }

    /** "경로|쪽" 형태 */
    private static List<Source> sources(String... specs) {
        List<Source> out = new ArrayList<>();
        for (String s : specs) {
            String[] p = s.split("\\|", -1);
            out.add(new Source(p[0], p[1]));
        }
        return out;
    }

    /** Draft 의 일부만 바꾼 복사본 */
    /** 문항 순서대로 번호(1부터)를 붙인 응시자 AI 답 */
    static com.pair.domain.taskgen.dto.SoloBizAnswerDto solo(String... a) {
        List<com.pair.domain.taskgen.dto.SoloBizItemDto> items = new ArrayList<>();
        for (int i = 0; i < a.length; i++) items.add(new com.pair.domain.taskgen.dto.SoloBizItemDto(i + 1, a[i]));
        return new com.pair.domain.taskgen.dto.SoloBizAnswerDto(items);
    }

    static BizDraftDto edit(BizDraftDto d, UnaryOperator<Builder> change) {
        return change.apply(new Builder(d)).build();
    }

    static final class Builder {
        String title, summary, code, category, checklist;
        List<String> description, requirements, followUps;
        List<DocDraft> documents;
        List<DevDraftDto.FileDraft> csvFiles;
        Trap trap;
        List<QuestionDraft> questions;
        List<String> trapKeywords;
        List<DevDraftDto.JudgmentPoint> judgmentPoints;
        List<DevDraftDto.HiddenFlaw> hiddenFlaws;

        Builder(BizDraftDto d) {
            title = d.title();
            summary = d.summary();
            code = d.code();
            category = d.category();
            checklist = d.checklist();
            description = d.description();
            requirements = d.requirements();
            followUps = d.followUps();
            documents = d.documents();
            csvFiles = d.csvFiles();
            trap = d.trap();
            questions = d.questions();
            trapKeywords = d.trapKeywords();
            judgmentPoints = d.judgmentPoints();
            hiddenFlaws = d.hiddenFlaws();
        }

        Builder questions(List<QuestionDraft> q) { questions = q; return this; }
        Builder documents(List<DocDraft> v) { documents = v; return this; }
        Builder csvFiles(List<DevDraftDto.FileDraft> v) { csvFiles = v; return this; }
        Builder trap(Trap v) { trap = v; return this; }
        Builder description(List<String> v) { description = v; return this; }
        Builder code(String v) { code = v; return this; }
        Builder requirements(List<String> v) { requirements = v; return this; }
        Builder followUps(List<String> v) { followUps = v; return this; }
        Builder trapKeywords(List<String> v) { trapKeywords = v; return this; }
        Builder judgmentPoints(List<DevDraftDto.JudgmentPoint> v) { judgmentPoints = v; return this; }
        Builder hiddenFlaws(List<DevDraftDto.HiddenFlaw> v) { hiddenFlaws = v; return this; }

        BizDraftDto build() {
            return new BizDraftDto(title, summary, code, category, description, requirements, followUps, documents, csvFiles, checklist, trap, questions, null, null, trapKeywords, judgmentPoints, hiddenFlaws);
        }
    }

    /** 문항을 하나만 바꾼 목록 */
    static List<QuestionDraft> replaceQuestion(BizDraftDto d, int index, QuestionDraft q) {
        List<QuestionDraft> out = new ArrayList<>(d.questions());
        out.set(index, q);
        return out;
    }

    /** 정해 둔 답을 돌려주는 가짜 풀이 모델 */
    static final class FakeSolver implements TaskGenSolverService {
        final AtomicInteger calls = new AtomicInteger();
        volatile String reply = "계산했습니다.\n{\"answers\": [\"1\", \"2\", \"21080\", \"2000\"]}";
        volatile RuntimeException error;
        volatile String lastPrompt;
        volatile String lastModel;

        @Override
        public String solve(String model, String prompt) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            lastModel = model;
            if (error != null) throw error;
            return reply;
        }
    }
}
