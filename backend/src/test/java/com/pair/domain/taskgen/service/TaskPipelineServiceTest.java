package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 단계별 생성: AI 호출을 가짜로 바꿔 단계 순서, 작성 입력의 정보 가리기, 조립, 다시 돌릴 단계 고르기를 확인한다 */
class TaskPipelineServiceTest {

    /** 단계 결과 타입별로 미리 정한 값을 차례로 돌려주고, 부른 순서와 입력·설정을 기록하는 가짜 AI */
    static class StageAi implements TaskGenAiService {
        final Map<Class<?>, Deque<Object>> script = new HashMap<>();
        final List<Class<?>> order = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        final List<String> efforts = new ArrayList<>();

        <T> StageAi add(Class<T> type, T value) {
            script.computeIfAbsent(type, k -> new ArrayDeque<>()).add(value);
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
            return generate(model, system, user, type, 1000, "medium");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
            order.add(type);
            users.add(user);
            efforts.add(effort);
            Deque<Object> q = script.get(type);
            Object v = q.size() > 1 ? q.poll() : q.peek(); // 마지막 값은 계속 쓴다
            return new Output<>((T) v, 100, 200);
        }
    }

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final TaskGenRequest DEV_FORM = new TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20);
    private static final TaskGenRequest BIZ_FORM = new TaskGenRequest("BIZ", "캠페인 검토", "지표 정의 차이", "SELF", 20);

    // ── DEV 고정값 ─────────────────────────────

    static final String F_DIGITS = "입력 금액은 0 이상의 십진수 문자열이며 소수점 아래는 최대 세 자리입니다.";
    static final String F_SETTLE = "정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다.";
    static final String F_REPORT = "리포트 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 -2.00으로 표시합니다.";
    static final String EX1_IN = "새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount()";
    static final String EX2_IN = "새 PaymentDiscount에 할인 0.10, 0.20을 등록한 뒤 totalDiscount()";

    static BlueprintDto devBlueprint() {
        return new BlueprintDto("누적 할인을 반영한 결제액", "할인 합계를 반영하는 결제액 조회를 추가합니다.", "payment-discounts", "기능 구현", List.of("control"),
                "결제 화면에서 할인 항목을 등록하고 합계를 조회한다.", List.of("PaymentDiscount.totalDiscount(): BigDecimal"),
                List.of(new BlueprintDto.Fact(F_DIGITS, "description"), new BlueprintDto.Fact(F_SETTLE, "description"), new BlueprintDto.Fact(F_REPORT, "description")),
                List.of(new BlueprintDto.Example(EX1_IN, "3.50"), new BlueprintDto.Example(EX2_IN, "0.30")),
                List.of(new BlueprintDto.Flaw("할인을 double 로 누적해 소수 합계가 어긋난다", "PaymentDiscount.addDiscount", F_DIGITS, "totalsExactly")),
                List.of(new BlueprintDto.Judgment("할인이 원금보다 클 때 결제액을 어떻게 보일 것인가", F_SETTLE,
                        List.of(new BlueprintDto.Option("0으로 보인다", F_SETTLE), new BlueprintDto.Option("음수로 보인다", F_REPORT)))),
                List.of("double", "부동소수점"), null, List.of(), List.of(), List.of(), List.of());
    }

    static DevWritingDto devWriting() {
        return new DevWritingDto(List.of("결제 화면 코드에 결제액 조회를 추가하세요. " + F_DIGITS, F_SETTLE + " " + F_REPORT + " 어느 쪽으로 정할지 설명하세요."),
                List.of(EX1_IN + " → 3.50", EX2_IN + " → 0.30"), "");
    }

    static DevCodeDto devCode() {
        return new DevCodeDto(List.of(new DevDraftDto.FileDraft("src/shop/PaymentDiscount.java", "package shop;\npublic class PaymentDiscount {}\n"),
                new DevDraftDto.FileDraft("test/shop/PaymentDiscountTest.java", "package shop;\nclass PaymentDiscountTest {}\n")));
    }

    static DevAnswerDto devAnswer() {
        var sol = List.of(new DevDraftDto.FileDraft("src/shop/PaymentDiscount.java", "package shop;\npublic class PaymentDiscount { int x; }\n"));
        return new DevAnswerDto("# 정답", List.of(new DevDraftDto.FileDraft("test/shop/PaymentDiscountHiddenTest.java", "package shop;\nclass H {}\n")), sol,
                List.of(new DevDraftDto.AltSolution("다른 구조", sol)), List.of(new DevDraftDto.WrongFix("double 누적", sol), new DevDraftDto.WrongFix("반올림", sol)), "AI 수정이 다른 입력에서도 맞는지 어떻게 확인했나요?");
    }

    private static StageAi devAi() {
        return new StageAi().add(BlueprintDto.class, devBlueprint()).add(DevWritingDto.class, devWriting())
                .add(DevCodeDto.class, devCode()).add(DevAnswerDto.class, devAnswer());
    }

    private static TaskPipelineService.Run<DevDraftDto> devRun(StageAi ai) {
        return new DevPipelineService(ai, PROPS).start(DEV_FORM);
    }

    private static GenCheckResultDto fail(String name, String detail) {
        return new GenCheckResultDto(name, false, detail);
    }

    // ── 단계 순서·조립 ─────────────────────────────

    @Test
    @DisplayName("DEV: 설계도 → 작성 → 시작 코드 → 정답 쪽 순서로 부르고, 단계별 추론 강도와 토큰을 기록한다")
    void devStageOrderAndUsage() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});

        assertThat(ai.order).containsExactly(BlueprintDto.class, DevWritingDto.class, DevCodeDto.class, DevAnswerDto.class);
        assertThat(ai.efforts).containsExactly("high", "medium", "medium", "medium");
        assertThat(run.usage()).extracting("stage").containsExactly("BLUEPRINT", "WRITING", "CODE", "ANSWER");
        assertThat(run.usage()).allSatisfy(u -> {
            assertThat(u.inputTokens()).isEqualTo(100);
            assertThat(u.outputTokens()).isEqualTo(200);
        });
        assertThat(ai.users.get(3)).contains("[응시자에게 보이는 글]").contains("PaymentDiscountTest").contains("totalsExactly"); // 정답 쪽은 전부 안다
    }

    @Test
    @DisplayName("DEV 조립: 기존 DevDraftDto 로 합치고 판단 근거·결함·후속 질문을 설계도에서 가져온다")
    void devAssembly() {
        DevDraftDto d = devRun(devAi()).create(s -> {});

        assertThat(d.title()).isEqualTo("누적 할인을 반영한 결제액");
        assertThat(d.difficulty()).isEqualTo("쉬움"); // 20분
        assertThat(d.files()).extracting(DevDraftDto.FileDraft::path).containsExactly("src/shop/PaymentDiscount.java", "test/shop/PaymentDiscountTest.java");
        assertThat(d.hiddenTests()).hasSize(1);
        assertThat(d.wrongFixes()).hasSize(2);
        assertThat(d.taskFollowUp()).contains("어떻게 확인했나요");
        assertThat(d.trapKeywords()).containsExactly("double", "부동소수점");
        assertThat(d.hiddenFlaws().get(0).caughtBy()).isEqualTo("totalsExactly");
        assertThat(d.judgmentPoints().get(0).evidence()).containsExactly(F_SETTLE, F_REPORT);
        assertThat(DevRunService.formatProblem(d)).isNull(); // 기존 형식 검사를 그대로 통과한다
    }

    @Test
    @DisplayName("DEV 조립: 접수 메모(notes)가 있으면 NOTES.md 로 시작 코드 파일에 붙는다")
    void devNotesBecomeFile() {
        StageAi ai = devAi().add(DevWritingDto.class, new DevWritingDto(devWriting().description(), devWriting().requirements(), "접수 메모: 합계가 어긋남"));
        ai.script.get(DevWritingDto.class).poll(); // 처음 값 대신 notes 가 있는 값만 남긴다
        DevDraftDto d = devRun(ai).create(s -> {});
        assertThat(d.files()).extracting(DevDraftDto.FileDraft::path).contains("NOTES.md");
    }

    // ── 정보 가리기 ─────────────────────────────

    @Test
    @DisplayName("정보 가리기(DEV): 작성 입력에는 사실·예가 모두 있고 결함 설명·위치·잡는 테스트·함정 핵심어는 없다")
    void writingInputHidesFlaw() {
        StageAi ai = devAi();
        devRun(ai).create(s -> {});
        String input = ai.users.get(1);

        for (String f : List.of(F_DIGITS, F_SETTLE, F_REPORT, EX1_IN, EX2_IN)) assertThat(input).contains(f);
        BlueprintDto b = devBlueprint();
        for (String secret : List.of(b.flaws().get(0).what(), b.flaws().get(0).where(), b.flaws().get(0).caughtBy(), "double", "부동소수점",
                "0으로 보인다", "음수로 보인다")) {
            assertThat(input).as(secret).doesNotContain(secret);
        }
        assertThat(input).doesNotContain("결함", "함정", "flaw");
    }

    @Test
    @DisplayName("정보 가리기(BIZ): 작성 입력에는 문서 계획·CSV·사실이 있고 결함·결론·문항 계획·핵심어는 없다")
    void bizWritingInputHidesFlaw() {
        BlueprintDto b = bizBlueprint();
        String input = TaskWritingService.input(b, true, 20, "");

        assertThat(input).contains(BIZ_F1, BIZ_F2, BIZ_F3, "자료/검토 안내.pdf", "자료/캠페인 실적.csv", "새봄,4월,10000,400");
        for (String secret : List.of(b.flaws().get(0).what(), b.flaws().get(0).where(), b.conclusion().correct(), b.conclusion().wrong(), "Q2", "퍼센트포인트")) {
            assertThat(input).as(secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("정보 가리기: 사실 목록은 결함 근거와 판단 근거를 따로 표시하지 않고 같은 모양으로 섞어 준다(자리별 그룹만)")
    void factsAreMixedWithoutMarks() {
        String input = TaskWritingService.input(devBlueprint(), false, 20, "");
        assertThat(input).contains("사실 목록 [place=description]:");
        long bullets = input.lines().filter(l -> l.startsWith("- ") && (l.contains("화면은") || l.contains("입력 금액은"))).count();
        assertThat(bullets).isEqualTo(3);
    }

    @Test
    @DisplayName("정보 가리기: 작성 단계를 다시 쓸 때 안내에는 검사 상세(결함 핵심어)가 아니라 일반 규칙과 이전 글의 문장만 들어간다")
    void writerFeedbackHasNoCheckDetail() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        String leaked = F_SETTLE;
        run.redo(List.of(fail(DevCheckService.TRAP_WORDS, "응시자에게 보이는 곳에 함정 핵심어가 드러납니다: 설명에 ['double'] 그리고 '" + leaked + "'")), s -> {});

        String second = ai.users.get(4); // 설계도, 작성, 코드, 정답, 다시 작성 순서
        assertThat(ai.order.get(4)).isEqualTo(DevWritingDto.class);
        assertThat(second).contains("사실 목록에 없는 말로 원인이나 결함을 짚지 않는다").contains("고칠 문장: " + F_SETTLE.substring(0, 5));
        assertThat(second).doesNotContain("double", "부동소수점");
    }

    // ── 다시 돌릴 단계 ─────────────────────────────

    @Test
    @DisplayName("다시 돌리기: 시작 코드 검사 실패는 시작 코드와 정답 쪽만, 설계도·작성은 그대로")
    void redoFromCode() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(DevCheckService.START_PASSES, "컴파일 실패")), s -> {});

        assertThat(ai.order).containsExactly(DevCodeDto.class, DevAnswerDto.class);
        assertThat(run.redone()).containsExactly("CODE", "ANSWER");
        assertThat(ai.users.get(0)).contains("컴파일 실패"); // 코드 단계는 결함을 알므로 검사 상세를 그대로 받는다
    }

    @Test
    @DisplayName("다시 돌리기: 숨은 테스트 검사 실패는 정답 쪽만, 글 검사 실패는 작성과 정답 쪽")
    void redoFromAnswerOrWriting() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(DevCheckService.HIDDEN_CATCHES, "숨은 테스트가 시작 코드에서 모두 통과")), s -> {});
        assertThat(ai.order).containsExactly(DevAnswerDto.class);

        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(TaskPipelineService.FACTS_CHECK, "빠진 사실")), s -> {});
        assertThat(ai.order).containsExactly(DevWritingDto.class, DevAnswerDto.class);
    }

    @Test
    @DisplayName("다시 돌리기: 어느 단계인지 모르는 검사가 있으면 설계도부터 전부 다시")
    void redoUnknownFromBlueprint() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail("처음 보는 검사", "?"), fail(DevCheckService.SOLVED_PASSES, "실패")), s -> {});

        assertThat(ai.order).containsExactly(BlueprintDto.class, DevWritingDto.class, DevCodeDto.class, DevAnswerDto.class);
        assertThat(ai.users.get(0)).contains("처음 보는 검사");
    }

    @Test
    @DisplayName("설계도가 모자라면(사실 3개 미만) 뒤 단계로 가기 전에 설계도를 1번 더 받는다")
    void blueprintRepairedOnce() {
        BlueprintDto b = devBlueprint();
        BlueprintDto thin = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(),
                List.of(new BlueprintDto.Fact(F_SETTLE, "description")), b.examples(), List.of(new BlueprintDto.Flaw("x", "y", "", "z")), List.of(), b.trapKeywords(),
                null, null, null, null, null);
        StageAi ai = new StageAi().add(BlueprintDto.class, thin).add(BlueprintDto.class, b).add(DevWritingDto.class, devWriting())
                .add(DevCodeDto.class, devCode()).add(DevAnswerDto.class, devAnswer());
        devRun(ai).create(s -> {});

        assertThat(ai.order).startsWith(BlueprintDto.class, BlueprintDto.class, DevWritingDto.class);
        assertThat(ai.users.get(1)).contains("설계도가 아래 문제로 걸렸습니다").contains("사실 목록(facts)이 3개 이상");
    }

    @Test
    @DisplayName("작성 결과 확인: 사실이나 예가 글에 그대로 없으면 fail, 있으면 통과")
    void factsCheck() {
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        assertThat(run.checks()).extracting(GenCheckResultDto::ok).containsOnly(true);

        DevWritingDto missing = new DevWritingDto(List.of("결제 코드에 기능을 추가하세요."), List.of(EX1_IN + " → 3.50"), "");
        StageAi ai2 = new StageAi().add(BlueprintDto.class, devBlueprint()).add(DevWritingDto.class, missing).add(DevCodeDto.class, devCode()).add(DevAnswerDto.class, devAnswer());
        var run2 = devRun(ai2);
        run2.create(s -> {});
        var r = run2.checks().stream().filter(c -> c.name().equals(TaskPipelineService.FACTS_CHECK)).findFirst().orElseThrow();
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains(F_DIGITS);
    }

    // ── BIZ ─────────────────────────────

    static final String BIZ_F1 = "심사표의 통과선은 심사 변화율 22퍼센트 이상이다.";
    static final String BIZ_F2 = "운영팀은 새봄의 전환율을 1퍼센트 증가로 기록했다.";
    static final String BIZ_F3 = "6월 상담팀 처리 여력은 1050건이었다.";

    static BlueprintDto bizBlueprint() {
        return new BlueprintDto("캠페인 검토", "배정 후보를 검토합니다.", "campaign-review", "기획안 작성", List.of("control"), "구독 서비스 캠페인 예산을 검토한다.",
                List.of(), List.of(new BlueprintDto.Fact(BIZ_F1, "자료/검토 안내.pdf"), new BlueprintDto.Fact(BIZ_F2, "자료/운영 메모.pdf"), new BlueprintDto.Fact(BIZ_F3, "자료/운영 메모.pdf")),
                List.of(), List.of(new BlueprintDto.Flaw("1퍼센트는 변화 폭이고 심사 변화율은 상대 변화율이다", "운영 메모 대 검토 안내", BIZ_F2, "Q2")),
                List.of(new BlueprintDto.Judgment("먼저 할 작업", BIZ_F3, List.of(new BlueprintDto.Option("안내문", BIZ_F1), new BlueprintDto.Option("상담", BIZ_F3)))),
                List.of("퍼센트포인트", "상대 변화율"), new BlueprintDto.Conclusion("새봄만 후보로 정한다.", "모두 보류한다."),
                List.of(new BlueprintDto.Table("자료/캠페인 실적.csv", List.of("캠페인", "월", "방문자 수", "전환 건수"), List.of(List.of("새봄", "4월", "10000", "400"), List.of("새봄", "6월", "10000", "500")))),
                List.of(new BlueprintDto.Figure("통과선", "22", null, List.of()), new BlueprintDto.Figure("운영팀 기록", "1", null, List.of()), new BlueprintDto.Figure("상담 여력", "1050", null, List.of())),
                List.of(new BlueprintDto.Doc("자료/검토 안내.pdf", "검토 안내"), new BlueprintDto.Doc("자료/운영 메모.pdf", "운영 메모")),
                List.of("Q1 객관식 결론", "Q2 단답형 변화율", "Q3 객관식 여력"));
    }

    static BizWritingDto bizWriting(String extra) {
        return new BizWritingDto(List.of("캠페인 예산을 검토해 1페이지 기획안을 쓰세요."), List.of("결론과 근거를 담으세요."),
                List.of(new BizDraftDto.DocDraft("자료/검토 안내.pdf", List.of("검토 안내\n" + BIZ_F1 + " 비교 기간은 4월과 6월이다." + extra)),
                        new BizDraftDto.DocDraft("자료/운영 메모.pdf", List.of("운영 메모\n" + BIZ_F2 + " " + BIZ_F3))));
    }

    static BizAnswerDto bizAnswer() {
        return new BizAnswerDto("## 필수 항목\n1. 결론", List.of(new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q2. 변화율은?", List.of(), List.of("25"), "1", List.of())), "AI 가 계산한 값을 어느 칸과 맞춰 봤나요?");
    }

    private static TaskPipelineService.Run<BizDraftDto> bizRun(StageAi ai) {
        return new BizPipelineService(ai, PROPS).start(BIZ_FORM);
    }

    @Test
    @DisplayName("BIZ: 설계도 → 작성 → 정답 쪽 순서(시작 코드 단계 없음). CSV 는 숫자표에서 만들고 followUps 는 3개, 숫자 대조가 통과한다")
    void bizStagesAndAssembly() {
        StageAi ai = new StageAi().add(BlueprintDto.class, bizBlueprint()).add(BizWritingDto.class, bizWriting("")).add(BizAnswerDto.class, bizAnswer());
        var run = bizRun(ai);
        BizDraftDto d = run.create(s -> {});

        assertThat(ai.order).containsExactly(BlueprintDto.class, BizWritingDto.class, BizAnswerDto.class);
        assertThat(d.csvFiles().get(0).path()).isEqualTo("자료/캠페인 실적.csv");
        assertThat(d.csvFiles().get(0).content()).isEqualTo("캠페인,월,방문자 수,전환 건수\n새봄,4월,10000,400\n새봄,6월,10000,500\n");
        assertThat(d.followUps()).hasSize(3);
        assertThat(d.followUps().get(2)).contains("어느 칸");
        assertThat(d.trap().evidence()).isEqualTo(BIZ_F2);
        assertThat(d.trap().wrongConclusion()).isEqualTo("모두 보류한다.");
        assertThat(d.hiddenFlaws().get(0).caughtBy()).isEqualTo("Q2");
        assertThat(run.checks()).extracting(GenCheckResultDto::ok).containsOnly(true);
        assertThat(ai.users.get(2)).contains("campaign-review").contains("새봄,4월,10000,400").doesNotContain("\"facts\":[{");
    }

    @Test
    @DisplayName("BIZ 숫자 대조: 문서에 숫자표에 없는 숫자(37)가 있으면 fail, 월·쪽 같은 날짜·차례 숫자는 허용")
    void bizDocumentNumbers() {
        BlueprintDto b = bizBlueprint();
        assertThat(BizNumbersService.documentsCheck(b, bizWriting("").documents()).ok()).isTrue();
        var bad = BizNumbersService.documentsCheck(b, bizWriting(" 응답자는 37명이었다.").documents());
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).contains("37");
        assertThat(BizNumbersService.documentsCheck(b, bizWriting(" 1,050건과 같다.").documents()).ok()).isTrue(); // 쉼표 표기도 같은 수
    }

    @Test
    @DisplayName("BIZ 숫자표 정합성: 부분이 전체보다 크거나 합계가 안 맞거나 사실의 숫자가 표에 없으면 문제")
    void bizBlueprintNumberProblems() {
        BlueprintDto b = bizBlueprint();
        assertThat(BizNumbersService.blueprintProblems(b)).isEmpty();

        List<BlueprintDto.Figure> figs = new ArrayList<>(b.figures());
        figs.add(new BlueprintDto.Figure("신청 전체", "500", null, List.of()));
        figs.add(new BlueprintDto.Figure("완료", "600", "신청 전체", List.of()));
        figs.add(new BlueprintDto.Figure("합계", "1000", null, List.of("신청 전체", "완료")));
        BlueprintDto bad = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(),
                List.of(new BlueprintDto.Fact("조사 응답은 37명이었다.", "자료/검토 안내.pdf")), b.examples(), b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(),
                b.tables(), figs, b.docs(), b.questionPlan());
        assertThat(BizNumbersService.blueprintProblems(bad)).anyMatch(p -> p.contains("부분이 전체보다 큽니다"))
                .anyMatch(p -> p.contains("합계가 맞지 않습니다")).anyMatch(p -> p.contains("37"));
    }

    @Test
    @DisplayName("BIZ 다시 돌리기: 숫자 대조 실패는 작성과 정답 쪽만, 결론이 같은 함정(CONCLUSION)은 설계도부터")
    void bizRedoRouting() {
        StageAi ai = new StageAi().add(BlueprintDto.class, bizBlueprint()).add(BizWritingDto.class, bizWriting("")).add(BizAnswerDto.class, bizAnswer());
        var run = bizRun(ai);
        run.create(s -> {});
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(BizNumbersService.NAME, "숫자 37")), s -> {});
        assertThat(ai.order).containsExactly(BizWritingDto.class, BizAnswerDto.class);

        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(BizCheckService.CONCLUSION, "결론이 같음")), s -> {});
        assertThat(ai.order).containsExactly(BlueprintDto.class, BizWritingDto.class, BizAnswerDto.class);
    }

    @Test
    @DisplayName("회귀: 설계도에 같은 경로의 표가 두 개면 설계도 확인이 잡고, 조립한 CSV·문서 경로는 한 번씩만 나온다(저장 때 task_files 경로 중복 방지)")
    void duplicatePathsAreNeverAssembledTwice() {
        BlueprintDto b = bizBlueprint();
        var t = b.tables().get(0);
        var dup = new BlueprintDto.Table(t.path(), t.columns(), List.of(List.of("여름", "6월", "10000", "600")));
        BlueprintDto twice = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), List.of(t, dup), b.figures(), b.docs(), b.questionPlan());

        assertThat(BlueprintService.problems(BlueprintService.normalized(twice, true), true)).anyMatch(p -> p.contains("자료 경로가 두 번 있습니다"));
        assertThat(BizNumbersService.csvFiles(twice)).extracting(DevDraftDto.FileDraft::path).containsExactly("자료/캠페인 실적.csv");

        // 같은 경로의 문서를 두 번 쓴 작성 결과도 한 번만 남는다
        var doc = bizWriting("").documents().get(0);
        BizWritingDto writing = new BizWritingDto(List.of("설명"), List.of("요구"), List.of(doc, doc, bizWriting("").documents().get(1)));
        StageAi ai = new StageAi().add(BlueprintDto.class, twice).add(BizWritingDto.class, writing).add(BizAnswerDto.class, bizAnswer());
        BizDraftDto d = bizRun(ai).create(s -> {});
        List<String> paths = new ArrayList<>(d.csvFiles().stream().map(DevDraftDto.FileDraft::path).toList());
        d.documents().forEach(x -> paths.add(x.path()));
        assertThat(paths).doesNotHaveDuplicates().hasSize(3);
    }

    // ── 경로 중복: 파일 주인과 안전장치 ─────────────────────────────

    private static DevDraftDto.FileDraft f(String path) {
        return new DevDraftDto.FileDraft(path, "x");
    }

    /** 저장 코드(GeneratedTaskRepository.saveDevOnce)가 task_files 에 넣는 경로를 그대로 모은다 */
    private static List<String> savedPaths(DevDraftDto d) {
        List<String> p = new ArrayList<>();
        d.files().forEach(x -> p.add(x.path()));
        d.hiddenTests().forEach(x -> p.add(x.path().substring("test/".length())));
        p.add("answer.md");
        p.add("_gen/report.json");
        d.solution().forEach(x -> p.add("_gen/solution/" + x.path()));
        for (int i = 0; i < d.wrongFixes().size(); i++) {
            p.add("_gen/wrong-fixes/" + (i + 1) + "/reason.md");
            for (var x : d.wrongFixes().get(i).files()) p.add("_gen/wrong-fixes/" + (i + 1) + "/" + x.path());
        }
        for (int i = 0; i < d.altSolutions().size(); i++) {
            p.add("_gen/alt-solutions/" + (i + 1) + "/note.md");
            for (var x : d.altSolutions().get(i).files()) p.add("_gen/alt-solutions/" + (i + 1) + "/" + x.path());
        }
        return p;
    }

    @Test
    @DisplayName("DEV 경로: 어떤 단계가 같은 파일을 또 만들어도(NOTES.md, src, 숨은 테스트, 정답, 다른 풀이, 오답) 조립 결과는 저장 경로가 겹치지 않고, 겹침은 경로 검사가 실패로 알린다")
    void devAssemblyNeverDuplicatesPaths() {
        var src = f("src/shop/PaymentDiscount.java");
        var pub = f("test/shop/PaymentDiscountTest.java");
        var hid = f("test/shop/PaymentDiscountHiddenTest.java");
        var sol = List.of(src);
        DevAnswerDto base = devAnswer();
        // [시작 코드 단계 파일, 정답 쪽 단계]. 각 줄이 겹침 하나
        List<DevCodeDto> codes = List.of(
                new DevCodeDto(List.of(src, pub, f("NOTES.md"))), // 시작 코드 단계가 NOTES.md 까지 만듦(주인은 작성 단계)
                new DevCodeDto(List.of(src, src, pub)),
                new DevCodeDto(List.of(src, pub, hid)), // 숨은 테스트를 시작 코드 단계가 만듦(공개 쪽에 들어옴)
                devCode());
        List<DevAnswerDto> answers = List.of(devAnswer(),
                new DevAnswerDto("a", List.of(hid, hid), sol, base.altSolutions(), base.wrongFixes(), "q"),
                new DevAnswerDto("a", List.of(hid, pub), sol, base.altSolutions(), base.wrongFixes(), "q"), // 숨은 테스트가 공개 테스트와 같은 경로
                new DevAnswerDto("a", List.of(hid), List.of(src, src), base.altSolutions(), base.wrongFixes(), "q"),
                new DevAnswerDto("a", List.of(hid), sol, List.of(new DevDraftDto.AltSolution("n", List.of(src, src))), base.wrongFixes(), "q"),
                new DevAnswerDto("a", List.of(hid), sol, base.altSolutions(), List.of(new DevDraftDto.WrongFix("r", List.of(src, src))), "q"),
                new DevAnswerDto("a", List.of(hid, f("src/shop/Other.java")), List.of(src, pub), base.altSolutions(), base.wrongFixes(), "q")); // 정답에 테스트 파일
        var withNotes = new DevWritingDto(devWriting().description(), devWriting().requirements(), "접수 메모");
        int combos = 0;
        for (DevCodeDto c : codes) for (DevAnswerDto a : answers) for (DevWritingDto w : List.of(devWriting(), withNotes)) {
            combos++;
            DevDraftDto d = TaskAssemblyService.dev(DEV_FORM, devBlueprint(), w, c, a);
            assertThat(savedPaths(d)).as("조합 %d", combos).doesNotHaveDuplicates();
        }
        assertThat(combos).isEqualTo(56);
        // 안전장치: 겹침이 있으면 말해 주고, 없으면 비어 있다
        assertThat(TaskAssemblyService.devPathProblems(devCode(), devAnswer())).isEmpty();
        assertThat(TaskAssemblyService.devPathProblems(codes.get(1), devAnswer())).anyMatch(p -> p.contains("src/shop/PaymentDiscount.java (시작 코드)"));
        assertThat(TaskAssemblyService.devPathProblems(devCode(), answers.get(1))).anyMatch(p -> p.contains("(숨은 테스트)"));
        assertThat(TaskAssemblyService.devPathProblems(devCode(), answers.get(3))).anyMatch(p -> p.contains("(정답)"));
        assertThat(TaskAssemblyService.devPathProblems(devCode(), answers.get(4))).anyMatch(p -> p.contains("(다른 풀이 1)"));
        assertThat(TaskAssemblyService.devPathProblems(devCode(), answers.get(5))).anyMatch(p -> p.contains("(오답 1)"));
    }

    @Test
    @DisplayName("DEV 경로 안전장치: 겹친 결과는 '파일 경로 중복 없음' fail 로 알리고 다시 돌릴 단계를 고른다(시작 코드 → 코드, 그 밖 → 정답 쪽). 저장되는 Draft 에는 겹침이 없다")
    void devPathCheckFailsAndRoutes() {
        DevAnswerDto dupHidden = new DevAnswerDto("a", List.of(f("test/shop/A.java"), f("test/shop/A.java")), devAnswer().solution(), devAnswer().altSolutions(), devAnswer().wrongFixes(), "q");
        StageAi ai = new StageAi().add(BlueprintDto.class, devBlueprint()).add(DevWritingDto.class, devWriting())
                .add(DevCodeDto.class, new DevCodeDto(List.of(f("src/shop/X.java"), f("src/shop/X.java"), f("test/shop/XTest.java"))))
                .add(DevAnswerDto.class, dupHidden);
        var run = devRun(ai);
        DevDraftDto d = run.create(s -> {});

        assertThat(savedPaths(d)).doesNotHaveDuplicates();
        var check = run.checks().stream().filter(c -> c.name().equals(TaskPipelineService.PATHS_CHECK)).findFirst().orElseThrow();
        assertThat(check.ok()).isFalse();
        assertThat(check.needsRegeneration()).isTrue(); // 기존 실패 흐름(다시 만들기 → 그래도 실패하면 실패 표시와 함께 저장)
        assertThat(DevPipelineService.owners(check, List.of())).containsExactly(TaskPipelineService.Stage.CODE);
        assertThat(DevPipelineService.owners(fail(TaskPipelineService.PATHS_CHECK, "같은 경로가 두 번 있습니다: a (숨은 테스트)"), List.of())).containsExactly(TaskPipelineService.Stage.ANSWER);
    }

    @Test
    @DisplayName("BIZ 경로: CSV 는 숫자표에서만, 문서는 문서 계획 경로로만 만든다. 표·문서 중복, 문서가 CSV 경로를 쓰거나 계획에 없는 경로를 써도 조립 결과 경로는 한 번씩이고 안전장치가 알린다")
    void bizAssemblyNeverDuplicatesPaths() {
        BlueprintDto b = bizBlueprint();
        var t = b.tables().get(0);
        var otherTable = new BlueprintDto.Table(t.path(), t.columns(), t.rows());
        var docA = bizWriting("").documents().get(0);
        var docB = bizWriting("").documents().get(1);
        var asCsv = new BizDraftDto.DocDraft("자료/캠페인 실적.csv", List.of("표 글"));
        var unplanned = new BizDraftDto.DocDraft("자료/계획 밖.pdf", List.of("글"));
        List<List<BlueprintDto.Table>> tableSets = List.of(List.of(t), List.of(t, otherTable));
        List<List<BizDraftDto.DocDraft>> docSets = List.of(List.of(docA, docB), List.of(docA, docA, docB), List.of(docA, asCsv, docB), List.of(docB, unplanned, docA, docB));
        int combos = 0;
        for (var tables : tableSets) for (var docs : docSets) {
            combos++;
            BlueprintDto bp = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(),
                    b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), tables, b.figures(), b.docs(), b.questionPlan());
            BizWritingDto w = new BizWritingDto(List.of("설명"), List.of("요구"), docs);
            BizDraftDto d = TaskAssemblyService.biz(BIZ_FORM, bp, w, bizAnswer());
            List<String> paths = new ArrayList<>(d.csvFiles().stream().map(DevDraftDto.FileDraft::path).toList());
            d.documents().forEach(x -> paths.add(x.path()));
            paths.addAll(List.of("기획안.md", "checklist.md", "_gen/report.json"));
            assertThat(paths).as("조합 %d", combos).doesNotHaveDuplicates();
            assertThat(d.documents()).extracting(BizDraftDto.DocDraft::path).containsExactly("자료/검토 안내.pdf", "자료/운영 메모.pdf");
        }
        assertThat(combos).isEqualTo(8);
        assertThat(TaskAssemblyService.bizPathProblems(b, bizWriting(""))).isEmpty();
        assertThat(TaskAssemblyService.bizPathProblems(b, new BizWritingDto(List.of("a"), List.of("b"), List.of(docA, docA)))).anyMatch(p -> p.contains("같은 경로가 두 번"));
        assertThat(TaskAssemblyService.bizPathProblems(b, new BizWritingDto(List.of("a"), List.of("b"), List.of(docA, asCsv)))).anyMatch(p -> p.contains("문서 계획에 없는 경로"));
        assertThat(BizPipelineService.owners(fail(TaskPipelineService.PATHS_CHECK, "x"))).containsExactly(TaskPipelineService.Stage.WRITING);
    }

    // ── 사실은 기록·관측만, 작성은 배경만, 보기 확인 ─────────────────────────────

    private static BlueprintDto withFacts(BlueprintDto b, List<BlueprintDto.Fact> facts) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), facts, b.examples(),
                List.of(new BlueprintDto.Flaw("x", "y", facts.get(0).text(), "z")), b.judgments().isEmpty() ? b.judgments() : List.of(
                new BlueprintDto.Judgment("q", facts.get(0).text(), List.of(new BlueprintDto.Option("a", facts.get(1).text()), new BlueprintDto.Option("b", facts.get(2).text())))),
                b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan());
    }

    @Test
    @DisplayName("설계도 사실 확인: 정의·공식·규칙·포함 문장은 걸러내고, 견적·청구서·로그 같은 기록은 통과시킨다")
    void blueprintRejectsRuleFacts() {
        List<String> rules = List.of("총비용은 공급가액에 봉사료와 부가가치세를 합한 금액이다.", "일평균 전환 수는 전환 수를 집행일 수로 나눈 값이다.",
                "집행일 수에는 시작일과 종료일이 모두 포함된다.", "채널 성과 비교 지표는 일평균 전환 수다.");
        List<String> records = List.of("견적서 항목: 대관 8,000,000원, 봉사료 10퍼센트 별도", "청구서 합계란에 '부가세 포함 금액'이라고 적혀 있다.",
                "7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨", "6월 상담팀 처리 여력은 1050건이었다.");
        rules.forEach(r -> assertThat(RuleSentenceService.ruleLike(r)).as(r).isTrue());
        records.forEach(r -> assertThat(RuleSentenceService.ruleLike(r)).as(r).isFalse());

        BlueprintDto b = devBlueprint();
        BlueprintDto bad = BlueprintService.normalized(withFacts(b, List.of(new BlueprintDto.Fact(rules.get(0), "description"), new BlueprintDto.Fact(records.get(0), "description"),
                new BlueprintDto.Fact(records.get(2), "notes"))), false);
        assertThat(BlueprintService.problems(bad, false)).anyMatch(p -> p.contains("정의·공식·규칙 문장") && p.contains("합한 금액"));
        BlueprintDto ok = BlueprintService.normalized(withFacts(b, List.of(new BlueprintDto.Fact(records.get(0), "description"), new BlueprintDto.Fact(records.get(1), "description"),
                new BlueprintDto.Fact(records.get(2), "notes"))), false);
        assertThat(BlueprintService.problems(ok, false)).isEmpty();
    }

    @Test
    @DisplayName("설계도에 규칙 문장이 있으면 뒤 단계로 가기 전에 설계도를 다시 받는다(고친 문장을 받으면 그것으로 진행)")
    void blueprintWithRuleFactIsRewritten() {
        BlueprintDto bad = withFacts(devBlueprint(), List.of(new BlueprintDto.Fact("총비용은 공급가액에 봉사료를 합한 금액이다.", "description"),
                new BlueprintDto.Fact(F_SETTLE, "description"), new BlueprintDto.Fact(F_REPORT, "description")));
        StageAi ai = new StageAi().add(BlueprintDto.class, bad).add(BlueprintDto.class, devBlueprint()).add(DevWritingDto.class, devWriting())
                .add(DevCodeDto.class, devCode()).add(DevAnswerDto.class, devAnswer());
        devRun(ai).create(s -> {});

        assertThat(ai.order).startsWith(BlueprintDto.class, BlueprintDto.class, DevWritingDto.class);
        assertThat(ai.users.get(1)).contains("설계도가 아래 문제로 걸렸습니다").contains("기록과 관측");
        assertThat(ai.users.get(2)).doesNotContain("합한 금액");
    }

    @Test
    @DisplayName("작성 후 확인: 사실·예 밖에 덧붙인 규칙·정의·용도 문장(구분자 취급, API 용도)은 fail 이고 작성 단계에서 다시 쓴다. 사실·예 문장과 배경은 통과")
    void writingAddedRulesAreRejected() {
        BlueprintDto b = devBlueprint();
        String background = "결제 팀은 매일 아침 어제의 정산 내역을 확인합니다. " + F_DIGITS;
        assertThat(TaskWritingService.rulesCheck(b, background + "\n" + EX1_IN + " → 3.50").ok()).isTrue();

        var bad = TaskWritingService.rulesCheck(b, background + "\n구분자는 문자 그대로 취급하며, 빈 필드도 결과에 포함합니다.\n이 API는 원문 보관 화면과 상세 화면에 사용됩니다.");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).contains("문자 그대로 취급하며").contains("사용됩니다");

        // 파이프라인: 작성 결과에 규칙 문장이 있으면 검사가 fail 이고, 작성 단계부터 다시 돈다
        DevWritingDto ruled = new DevWritingDto(List.of(devWriting().description().get(0), devWriting().description().get(1), "빈 필드도 결과에 포함합니다."), devWriting().requirements(), "");
        StageAi ai = new StageAi().add(BlueprintDto.class, b).add(DevWritingDto.class, ruled).add(DevCodeDto.class, devCode()).add(DevAnswerDto.class, devAnswer());
        var run = devRun(ai);
        run.create(s -> {});
        var check = run.checks().stream().filter(c -> c.name().equals(TaskPipelineService.RULES_CHECK)).findFirst().orElseThrow();
        assertThat(check.needsRegeneration()).isTrue();
        assertThat(DevPipelineService.owners(check, List.of())).containsExactly(TaskPipelineService.Stage.WRITING);
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(check), s -> {});
        assertThat(ai.order).containsExactly(DevWritingDto.class, DevAnswerDto.class);
        assertThat(ai.users.get(0)).contains("고칠 문장: 빈 필드도 결과에 포함합니다.").contains("규칙·조건·정의·용도 문장은 쓰지 않는다");
    }

    @Test
    @DisplayName("정답 쪽 보기 확인: 보기에 다른 문항의 정답 값이 있거나 판단 지점을 객관식 정답 하나로 내면 fail, 정상 문항은 통과")
    void answerChoiceCheck() {
        BlueprintDto b = bizBlueprint();
        var shortQ = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q1. 값은?", List.of(), List.of("12100000"), "1", List.of());
        BizDraftDto.QuestionDraft single = choiceQuestion("Q2. 어느 것?", "새봄만 후보로 정한다.", "모두 보류한다.", "후보 없음", "추가 검토");
        assertThat(AnswerChoiceService.check(b, List.of(shortQ, single)).ok()).isTrue();

        var leak = choiceQuestion("Q2. 어느 것?", "총 12,100,000원이다.", "모두 보류한다.", "후보 없음", "추가 검토");
        var r1 = AnswerChoiceService.check(b, List.of(shortQ, leak));
        assertThat(r1.ok()).isFalse();
        assertThat(r1.detail()).contains("Q1 의 정답 값 12100000");

        var judgment = choiceQuestion("Q2. 먼저 할 작업은?", "안내문을 먼저 한다.", "상담을 먼저 한다.", "둘 다 보류", "추가 검토");
        var r2 = AnswerChoiceService.check(b, List.of(shortQ, judgment));
        assertThat(r2.ok()).isFalse();
        assertThat(r2.detail()).contains("판단 지점").contains("객관식");
        assertThat(BizPipelineService.owners(r2)).containsExactly(TaskPipelineService.Stage.ANSWER);
    }

    private static BizDraftDto.QuestionDraft choiceQuestion(String content, String... choices) {
        List<BizDraftDto.Choice> list = new ArrayList<>();
        for (int i = 0; i < choices.length; i++) list.add(new BizDraftDto.Choice(choices[i], i == 0));
        return new BizDraftDto.QuestionDraft(BizDraftDto.Type.SINGLE, content, list, List.of(), "", List.of());
    }

    @Test
    @DisplayName("프롬프트: 설계도는 기록·관측만 쓰게 하고 사실 아님/사실임 예를 보이며, 작성은 배경만, 정답 쪽은 보기·판단 지점·결과 예를 막는다")
    void promptsCarryTheRules() {
        String bp = TaskPromptService.blueprint(true, 20, "깊이");
        assertThat(bp).contains("기록과 관측").contains("사실 아님:").contains("사실임:").contains("원자료 한 줄");
        assertThat(TaskPromptService.blueprint(false, 20, "깊이")).contains("시작 코드도 이미 맞게 내는 입력만");
        assertThat(TaskPromptService.WRITING_DEV).contains("배경뿐").contains("문자 그대로 취급한다");
        assertThat(TaskPromptService.writingBiz(20)).contains("배경뿐");
        assertThat(TaskPromptService.answerBiz("깊이")).contains("다른 문항의 정답 값을 넣지 않는다").contains("판단 지점은 객관식으로 묻지 않는다");
        assertThat(TaskPromptService.answerDev(20)).contains("보이는 예는 시작 코드가 이미 통과한다");
    }

    // ── 설정 ─────────────────────────────

    @Test
    @DisplayName("단계별 모델·추론 강도는 설정으로 바꾼다(기본: 설계도 high, 나머지 medium, 비우면 model)")
    void stageSettings() {
        assertThat(PROPS.stage("BLUEPRINT").effort()).isEqualTo("high");
        assertThat(PROPS.stage("ANSWER").effort()).isEqualTo("medium");
        assertThat(PROPS.stage("WRITING").model()).isEqualTo(PROPS.model());
        assertThat(PROPS.staged()).isTrue();

        var custom = new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, "oneshot",
                new TaskGenProperties.Stages(null, new TaskGenProperties.Stage("m-1", "low", 5000), null, null));
        assertThat(custom.staged()).isFalse();
        assertThat(custom.stage("WRITING")).isEqualTo(new TaskGenProperties.Stage("m-1", "low", 5000));
        assertThat(custom.stage("CODE").effort()).isEqualTo("medium");
    }

    @Test
    @DisplayName("TaskGenRequest 는 기존 그대로: 폼 검사를 통과한 DEV 폼으로 단계별 생성이 돈다")
    void formStillValid() {
        assertThat(DEV_FORM.validated().track()).isEqualTo("DEV");
    }
}
