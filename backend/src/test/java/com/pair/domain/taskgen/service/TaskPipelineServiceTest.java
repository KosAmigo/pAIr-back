package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
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
        final List<String> docUsers = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> systems = new ArrayList<>();
        final List<String> models = new ArrayList<>();
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
        public synchronized <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
            if (type == com.pair.domain.taskgen.dto.ReviewResultDto.class && !script.containsKey(type)) { // 설계도 직후의 싼 점검: 안 정해 두면 위반 없음, 단계 호출 순서에는 넣지 않는다
                @SuppressWarnings("unchecked")
                Output<T> clean = new Output<>((T) new com.pair.domain.taskgen.dto.ReviewResultDto(List.of()), 10, 10);
                return clean;
            }
            // 직군별 설계도(g6): 스크립트는 공통 BlueprintDto 로 적고, 부른 순서에도 BlueprintDto 로 남긴다
            if (type == com.pair.domain.taskgen.dto.DevBlueprintDto.class || type == com.pair.domain.taskgen.dto.BizBlueprintDto.class) {
                Output<BlueprintDto> o = generate(model, system, user, BlueprintDto.class, maxTokens, effort);
                Object converted = type == com.pair.domain.taskgen.dto.DevBlueprintDto.class ? com.pair.domain.taskgen.dto.DevBlueprintDto.from(o.value())
                        : com.pair.domain.taskgen.dto.BizBlueprintDto.from(o.value());
                return new Output<>((T) converted, o.inputTokens(), o.outputTokens());
            }
            // BIZ 작성 문서별 호출(g6): 문서 호출은 지금 맨 앞 작성 값을 보기만 하고(순서에 안 남김), 요청 글 호출이 그 값을 꺼낸다
            if (type == com.pair.domain.taskgen.dto.BizWritingDto.class && user.contains(BizPipelineService.DOC_CALL_MARK)) {
                docUsers.add(user); // g8: 문서만 다시 쓰는 호출을 확인하려고 따로 남긴다
                return new Output<>((T) script.get(type).peek(), 100, 200);
            }
            order.add(type);
            users.add(user);
            systems.add(system);
            models.add(model);
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
                List.of(new BlueprintDto.Flaw("할인을 double 로 누적해 소수 합계가 어긋난다", "DiscountLedger.add", F_DIGITS, "totalsExactly")),
                List.of(new BlueprintDto.Judgment("할인이 원금보다 클 때 결제액을 어떻게 보일 것인가", F_SETTLE,
                        List.of(new BlueprintDto.Option("0으로 보인다", F_SETTLE), new BlueprintDto.Option("음수로 보인다", F_REPORT)))),
                List.of("double", "부동소수점"), null, List.of(), List.of(), List.of(), List.of(),
                List.of(new BlueprintDto.Case("totalsExactly", "할인 0.10, 0.20을 등록한 뒤 합계", "0.30", F_DIGITS)));
    }

    static DevWritingDto devWriting() {
        return new DevWritingDto(List.of("결제 화면 코드에 결제액 조회를 추가하세요. " + F_DIGITS, F_SETTLE + " " + F_REPORT + " 어느 쪽으로 정할지 설명하세요."),
                List.of(EX1_IN + " → 3.50", EX2_IN + " → 0.30"), "", "AI 수정이 다른 입력에서도 맞는지 어떻게 확인했나요?");
    }

    static DevCodeDto devCode() {
        return new DevCodeDto(List.of(new DevDraftDto.FileDraft("src/shop/PaymentDiscount.java", "package shop;\npublic class PaymentDiscount {}\n"),
                new DevDraftDto.FileDraft("test/shop/PaymentDiscountTest.java", "package shop;\nclass PaymentDiscountTest {}\n")));
    }

    static DevAnswerDto devAnswer() {
        var sol = List.of(new DevDraftDto.FileDraft("src/shop/PaymentDiscount.java", "package shop;\npublic class PaymentDiscount { int x; }\n"));
        return new DevAnswerDto("# 정답", List.of(new DevDraftDto.FileDraft("test/shop/PaymentDiscountHiddenTest.java", "package shop;\nclass H {\n    @Test\n    void totalsExactly() {}\n}\n")), sol,
                List.of(new DevDraftDto.AltSolution("다른 구조", sol)), List.of(new DevDraftDto.WrongFix("double 누적", sol), new DevDraftDto.WrongFix("반올림", sol)));
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
        assertThat(ai.efforts).containsExactly("medium", "medium", "medium", "medium");
        assertThat(run.usage()).extracting("stage").containsExactly("BLUEPRINT", "REVIEW:BLUEPRINT", "WRITING", "CODE", "ANSWER"); // 설계도 직후 글 문장 점검 1번
        assertThat(run.usage()).filteredOn(u -> !u.stage().equals("REVIEW:BLUEPRINT")).allSatisfy(u -> {
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
        run.redo(List.of(fail(TaskPipelineService.FACTS_CHECK, "글에 그대로 들어가지 않은 사실: ['double' 원인] 그리고 '" + leaked + "'")), s -> {});

        String second = ai.users.get(4); // 설계도, 작성, 코드, 정답, 다시 작성 순서
        assertThat(ai.order.get(4)).isEqualTo(DevWritingDto.class);
        assertThat(second).contains("사실 목록의 문장과 예를 하나도 빼지 말고 그대로 넣는다").contains("고칠 문장: " + F_SETTLE.substring(0, 5));
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
    @DisplayName("다시 돌리기: 숨은 테스트 검사 실패는 정답 쪽만, 글 검사 실패는 작성만")
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
        assertThat(ai.order).containsExactly(DevWritingDto.class); // 글만 바뀌면 정답 쪽은 그대로(시작 코드가 바뀔 때만 정답 쪽도 다시)
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
                List.of("Q1 객관식 결론", "Q2 단답형 변화율", "Q3 객관식 여력"),
                List.of(new BlueprintDto.Case("Q1", "새봄·여름 결론", "새봄만 후보", BIZ_F1, "배정 후보 결론", List.of("새봄만 후보", "여름만 후보", "둘 다 후보", "둘 다 보류"), false),
                new BlueprintDto.Case("Q2", "새봄 변화율", "25", BIZ_F2, "새봄 4월 대비 6월 심사 변화율(퍼센트)", List.of(), false),
                new BlueprintDto.Case("Q3", "6월 여력", "부족", BIZ_F3, "6월 상담 여력의 충분 여부", List.of("부족", "충분", "같음", "알 수 없음"), false)));
    }

    static BizWritingDto bizWriting(String extra) {
        return new BizWritingDto(List.of("캠페인 예산을 검토해 1페이지 기획안을 쓰세요."), List.of("결론과 근거를 담으세요."),
                List.of(new BizDraftDto.DocDraft("자료/검토 안내.pdf", List.of("검토 안내\n" + BIZ_F1 + " 비교 기간은 4월과 6월이다." + extra)),
                        new BizDraftDto.DocDraft("자료/운영 메모.pdf", List.of("운영 메모\n" + BIZ_F2 + " " + BIZ_F3))),
                "AI 가 계산한 값을 어느 칸과 맞춰 봤나요?");
    }

    static BizQuestionsDto bizQuestions() {
        return new BizQuestionsDto(List.of(
                new BizQuestionsDto.Question("Q1", "Q1. 배정 후보는?", List.of("새봄만 후보", "여름만 후보", "둘 다 후보", "둘 다 보류")),
                new BizQuestionsDto.Question("Q2", "Q2. 새봄의 4월 대비 6월 심사 변화율은? (퍼센트, 정수)", List.of()),
                new BizQuestionsDto.Question("Q3", "Q3. 6월 상담 여력은?", List.of("부족", "충분", "같음", "알 수 없음"))));
    }

    static BizAnswerDto bizAnswer() {
        return new BizAnswerDto("## 필수 항목\n1. 결론", List.of(
                new BizAnswerDto.Key("Q1", List.of(), "", List.of(), List.of()),
                new BizAnswerDto.Key("Q2", List.of("25%"), "1", List.of(), List.of()),
                new BizAnswerDto.Key("Q3", List.of(), "", List.of(), List.of())));
    }

    private static TaskPipelineService.Run<BizDraftDto> bizRun(StageAi ai) {
        return new BizPipelineService(ai, PROPS).start(BIZ_FORM);
    }

    @Test
    @DisplayName("BIZ: 설계도 → 작성 → 문항 글 → 정답 쪽 순서(시작 코드 단계 없음). CSV 는 숫자표에서 만들고 followUps 는 3개, 숫자 대조가 통과한다")
    void bizStagesAndAssembly() {
        StageAi ai = new StageAi().add(BlueprintDto.class, bizBlueprint()).add(BizWritingDto.class, bizWriting("")).add(BizQuestionsDto.class, bizQuestions()).add(BizAnswerDto.class, bizAnswer());
        var run = bizRun(ai);
        BizDraftDto d = run.create(s -> {});

        assertThat(ai.order).containsExactly(BlueprintDto.class, BizWritingDto.class, BizQuestionsDto.class, BizAnswerDto.class);
        assertThat(d.csvFiles().get(0).path()).isEqualTo("자료/캠페인 실적.csv");
        assertThat(d.csvFiles().get(0).content()).isEqualTo("캠페인,월,방문자 수,전환 건수\n새봄,4월,10000,400\n새봄,6월,10000,500\n");
        assertThat(d.followUps()).hasSize(3);
        assertThat(d.followUps().get(2)).contains("어느 칸");
        assertThat(d.trap().evidence()).isEqualTo(BIZ_F2);
        assertThat(d.trap().wrongConclusion()).isEqualTo("모두 보류한다.");
        assertThat(d.hiddenFlaws().get(0).caughtBy()).isEqualTo("Q2");
        assertThat(run.checks()).extracting(GenCheckResultDto::ok).containsOnly(true);
        assertThat(ai.users.get(3)).contains("campaign-review").contains("새봄,4월,10000,400").doesNotContain("\"facts\":[{");
    }

    @Test
    @DisplayName("BIZ 숫자 대조: 문서에 숫자표에 없는 숫자(37)가 있으면 fail, 월·쪽 같은 날짜·차례 숫자는 허용")
    void bizDocumentNumbers() {
        BlueprintDto b = bizBlueprint();
        assertThat(BizNumbersService.documentsCheck(b, bizWriting("").documents()).ok()).isTrue();
        var bad = BizNumbersService.documentsCheck(b, bizWriting(" 응답자는 37건이었다.").documents());
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
                List.of(new BlueprintDto.Fact("조사 응답은 37건이었다.", "자료/검토 안내.pdf")), b.examples(), b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(),
                b.tables(), figs, b.docs(), b.questionPlan());
        assertThat(BizNumbersService.blueprintProblems(bad)).anyMatch(p -> p.contains("부분이 전체보다 큽니다"))
                .anyMatch(p -> p.contains("합계가 맞지 않습니다")).anyMatch(p -> p.contains("37"));
    }

    @Test
    @DisplayName("BIZ 다시 돌리기: 숫자 대조 실패는 작성과 정답 쪽, 문항 글 실패는 문항 글만, 결론이 같은 함정(CONCLUSION)은 설계도부터")
    void bizRedoRouting() {
        StageAi ai = new StageAi().add(BlueprintDto.class, bizBlueprint()).add(BizWritingDto.class, bizWriting("")).add(BizQuestionsDto.class, bizQuestions()).add(BizAnswerDto.class, bizAnswer());
        var run = bizRun(ai);
        run.create(s -> {});
        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(BizNumbersService.NAME, "숫자 37")), s -> {});
        assertThat(ai.order).containsExactly(BizWritingDto.class, BizAnswerDto.class); // 자료가 바뀌면 쪽 번호 근거가 바뀌므로 정답 쪽도

        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(BizQuestionService.CHECK, "보기가 다름")), s -> {});
        assertThat(ai.order).containsExactly(BizQuestionsDto.class); // 문항 글만

        ai.order.clear();
        ai.users.clear();
        run.redo(List.of(fail(BizCheckService.CONCLUSION, "결론이 같음")), s -> {});
        assertThat(ai.order).containsExactly(BlueprintDto.class, BizWritingDto.class, BizQuestionsDto.class, BizAnswerDto.class);
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
        StageAi ai = new StageAi().add(BlueprintDto.class, twice).add(BizWritingDto.class, writing).add(BizQuestionsDto.class, bizQuestions()).add(BizAnswerDto.class, bizAnswer());
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
                new DevAnswerDto("a", List.of(hid, hid), sol, base.altSolutions(), base.wrongFixes()),
                new DevAnswerDto("a", List.of(hid, pub), sol, base.altSolutions(), base.wrongFixes()), // 숨은 테스트가 공개 테스트와 같은 경로
                new DevAnswerDto("a", List.of(hid), List.of(src, src), base.altSolutions(), base.wrongFixes()),
                new DevAnswerDto("a", List.of(hid), sol, List.of(new DevDraftDto.AltSolution("n", List.of(src, src))), base.wrongFixes()),
                new DevAnswerDto("a", List.of(hid), sol, base.altSolutions(), List.of(new DevDraftDto.WrongFix("r", List.of(src, src)))),
                new DevAnswerDto("a", List.of(hid, f("src/shop/Other.java")), List.of(src, pub), base.altSolutions(), base.wrongFixes())); // 정답에 테스트 파일
        var withNotes = new DevWritingDto(devWriting().description(), devWriting().requirements(), "접수 메모");
        int combos = 0;
        for (DevCodeDto c : codes) for (DevAnswerDto a : answers) for (DevWritingDto w : List.of(devWriting(), withNotes)) {
            combos++;
            DevDraftDto d = TaskAssemblyService.dev(DEV_FORM, devBlueprint(), w, c, a);
            assertThat(savedPaths(d)).as("조합 %d", combos).doesNotHaveDuplicates();
        }
        assertThat(combos).isEqualTo(56);
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
            BizDraftDto d = TaskAssemblyService.biz(BIZ_FORM, bp, w, BizQuestionService.assemble(bp, bizQuestions(), bizAnswer()), bizAnswer());
            List<String> paths = new ArrayList<>(d.csvFiles().stream().map(DevDraftDto.FileDraft::path).toList());
            d.documents().forEach(x -> paths.add(x.path()));
            paths.addAll(List.of("기획안.md", "checklist.md", "_gen/report.json"));
            assertThat(paths).as("조합 %d", combos).doesNotHaveDuplicates();
            assertThat(d.documents()).extracting(BizDraftDto.DocDraft::path).containsExactly("자료/검토 안내.pdf", "자료/운영 메모.pdf");
        }
        assertThat(combos).isEqualTo(8);
    }

    // ── 사실은 기록·관측만, 작성은 배경만, 보기 확인 ─────────────────────────────

    @Test
    @DisplayName("정답 쪽 보기 확인: 보기에 다른 문항의 정답 값이 있으면 fail, 정상 문항은 통과")
    void answerChoiceCheck() {
        BlueprintDto b = bizBlueprint();
        var shortQ = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q1. 값은?", List.of(), List.of("12100000"), "1", List.of());
        BizDraftDto.QuestionDraft single = choiceQuestion("Q2. 어느 것?", "새봄만 후보로 정한다.", "모두 보류한다.", "후보 없음", "추가 검토");
        assertThat(AnswerChoiceService.check(b, List.of(shortQ, single)).ok()).isTrue();

        var leak = choiceQuestion("Q2. 어느 것?", "총 12,100,000원이다.", "모두 보류한다.", "후보 없음", "추가 검토");
        var r1 = AnswerChoiceService.check(b, List.of(shortQ, leak));
        assertThat(r1.ok()).isFalse();
        assertThat(r1.detail()).contains("Q1 의 정답 값 12100000");

        // 판단 지점을 객관식으로 낸 것은 이제 코드가 아니라 검수(4단계)가 본다
        var judgment = choiceQuestion("Q2. 먼저 할 작업은?", "안내문을 먼저 한다.", "상담을 먼저 한다.", "둘 다 보류", "추가 검토");
        assertThat(AnswerChoiceService.check(b, List.of(shortQ, judgment)).ok()).isTrue();
        assertThat(BizPipelineService.owners(r1)).containsExactly(TaskPipelineService.Stage.QUESTIONS);
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
        assertThat(bp).contains("records and observations").contains("Not a fact").contains("Fact: \"견적서").contains("one raw record line");
        assertThat(TaskPromptService.blueprint(false, 20, "깊이")).contains("must already pass on the flawed starter code");
        assertThat(TaskPromptService.WRITING_DEV).contains("write only background").contains("문자 그대로 취급한다");
        assertThat(TaskPromptService.writingBizDoc(20, false, 2)).contains("write only background");
        assertThat(TaskPromptService.QUESTIONS_BIZ).contains("Do not put another question's value").contains("copy the given candidates exactly");
        assertThat(TaskPromptService.answerBiz("깊이")).contains("You write only the answer keys").doesNotContain("taskFollowUp");
        assertThat(TaskPromptService.answerDev(20)).contains("situation input values exactly");
    }

    @Test
    @DisplayName("문항 근거 경로: 띄어쓰기·자료/ 앞부분·확장자 차이는 실제 경로로 고치고, 그래도 없는 경로는 저장 Draft 에서 빼며 검사가 fail 로 알린다(저장 때 task_file_id 가 비지 않게)")
    void questionSourcePathsAreResolvedOrRejected() {
        List<String> files = List.of("자료/캠페인 실적.csv", "자료/검토 안내.pdf", "자료/운영 메모.pdf");
        assertThat(TaskAssemblyService.resolvePath("자료/캠페인 실적.csv", files)).isEqualTo("자료/캠페인 실적.csv");
        assertThat(TaskAssemblyService.resolvePath("자료/캠페인실적.csv", files)).isEqualTo("자료/캠페인 실적.csv"); // 띄어쓰기
        assertThat(TaskAssemblyService.resolvePath("캠페인 실적.csv", files)).isEqualTo("자료/캠페인 실적.csv"); // 자료/ 앞부분
        assertThat(TaskAssemblyService.resolvePath("자료/검토 안내", files)).isEqualTo("자료/검토 안내.pdf"); // 확장자
        assertThat(TaskAssemblyService.resolvePath("자료/검토 안내.csv", files)).isEqualTo("자료/검토 안내.pdf"); // 같은 이름, 다른 확장자도 이름이 하나뿐이면 같은 파일
        assertThat(TaskAssemblyService.resolvePath("자료/없는 문서.pdf", files)).isNull();
        assertThat(TaskAssemblyService.resolvePath(null, files)).isNull();

        BlueprintDto b = bizBlueprint();
        BizWritingDto w = bizWriting("");
        var q = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q?", List.of(), List.of("25"), "1",
                List.of(new BizDraftDto.Source("캠페인실적.csv", ""), new BizDraftDto.Source("자료/검토 안내", "1"), new BizDraftDto.Source("자료/예약 매출.csv", "")));
        var badSources = List.of(new BizDraftDto.Source("캠페인실적.csv", ""), new BizDraftDto.Source("자료/검토 안내", "1"), new BizDraftDto.Source("자료/예약 매출.csv", ""));
        BizAnswerDto answer = new BizAnswerDto("## 필수 항목\n1. 결론", List.of(
                new BizAnswerDto.Key("Q1", List.of(), "", badSources, List.of()), new BizAnswerDto.Key("Q2", List.of("25"), "1", List.of(), List.of()),
                new BizAnswerDto.Key("Q3", List.of(), "", List.of(), List.of())));

        BizDraftDto d = TaskAssemblyService.biz(BIZ_FORM, b, w, List.of(q), answer);
        assertThat(d.questions().get(0).sources()).extracting(BizDraftDto.Source::path).containsExactly("자료/캠페인 실적.csv", "자료/검토 안내.pdf");
        List<String> saved = new ArrayList<>(d.csvFiles().stream().map(DevDraftDto.FileDraft::path).toList());
        d.documents().forEach(x -> saved.add(x.path()));
        assertThat(saved).containsAll(d.questions().get(0).sources().stream().map(BizDraftDto.Source::path).toList());

        var problems = TaskAssemblyService.bizSourceProblems(b, w, List.of(q));
        assertThat(problems).hasSize(1).first().asString().contains("Q1").contains("자료/예약 매출.csv");

        // 파이프라인: 검사가 fail 이고 정답 쪽부터 다시 돈다
        StageAi ai = new StageAi().add(BlueprintDto.class, b).add(BizWritingDto.class, w).add(BizQuestionsDto.class, bizQuestions()).add(BizAnswerDto.class, answer);
        var run = bizRun(ai);
        run.create(s -> {});
        var check = run.checks().stream().filter(c -> c.name().equals(TaskPipelineService.SOURCE_PATH_CHECK)).findFirst().orElseThrow();
        assertThat(check.needsRegeneration()).isTrue();
        assertThat(BizPipelineService.owners(check)).containsExactly(TaskPipelineService.Stage.ANSWER);
        // 모두 맞는 경로면 통과
        var good = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q?", List.of(), List.of("25"), "1", List.of(new BizDraftDto.Source("캠페인 실적.csv", "")));
        assertThat(TaskAssemblyService.bizSourceProblems(b, w, List.of(good))).isEmpty();
    }

    // ── 설계도 사례(cases), 숫자·쪽 번호 읽기 ─────────────────────────────

    private static BlueprintDto withCases(BlueprintDto b, List<BlueprintDto.Case> cases) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(), b.flaws(),
                b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), cases);
    }

    @Test
    @DisplayName("설계도 사례: 사례가 없거나 결함의 caughtBy 가 사례에 없으면 설계도 문제이고, 사례의 근거 문장은 사실 목록에 자동으로 들어가 자료에 반드시 나온다")
    void blueprintCases() {
        BlueprintDto b = devBlueprint();
        assertThat(BlueprintService.problems(BlueprintService.normalized(b, false), false)).isEmpty();

        assertThat(BlueprintService.problems(BlueprintService.normalized(withCases(b, List.of()), false), false)).anyMatch(p -> p.contains("사례(cases)가 결함 수 이상"));
        var wrongName = withCases(b, List.of(new BlueprintDto.Case("otherTest", "상황", "0.30", F_DIGITS)));
        assertThat(BlueprintService.problems(BlueprintService.normalized(wrongName, false), false)).anyMatch(p -> p.contains("caughtBy 는 사례(cases)의 name"));

        String basis = "7월 3일 접수 로그: 할인 0.10, 0.20 합계 0.30000000000000004";
        var added = BlueprintService.normalized(withCases(b, List.of(new BlueprintDto.Case("totalsExactly", "상황", "0.30", basis))), false);
        assertThat(added.facts()).extracting(BlueprintDto.Fact::text).contains(basis); // 근거가 사실로 들어가 글에 그대로 실린다
        // 작성 단계는 사례를 받지 않는다(사실 목록으로만 본다)
        assertThat(TaskWritingService.input(added, false, 20, "")).doesNotContain("totalsExactly").doesNotContain("cases");
    }

    @Test
    @DisplayName("답 쪽이 설계도 사례만 구현했는지: 숨은 테스트 메서드가 사례 name 과 같아야 하고, 사례 밖 테스트(자료에 근거 없는 동작)나 빠진 사례는 fail, BIZ 는 문항 수가 사례 수와 같아야 한다")
    void caseCheck() {
        BlueprintDto b = devBlueprint();
        var ok = List.of(new DevDraftDto.FileDraft("test/shop/H.java", "class H {\n    @Test\n    void totalsExactly() {}\n}"));
        assertThat(CaseCheckService.dev(b, ok).ok()).isTrue();

        var extra = List.of(new DevDraftDto.FileDraft("test/shop/H.java", "class H {\n    @Test\n    void totalsExactly() {}\n    @Test\n    void upcomingZeroIsEmpty() {}\n}"));
        var r1 = CaseCheckService.dev(b, extra);
        assertThat(r1.ok()).isFalse();
        assertThat(r1.detail()).contains("upcomingZeroIsEmpty").contains("자료에 근거 없는 동작");
        assertThat(r1.needsRegeneration()).isTrue();

        var missing = List.of(new DevDraftDto.FileDraft("test/shop/H.java", "class H {\n    @Test\n    void other() {}\n}"));
        assertThat(CaseCheckService.dev(b, missing).detail()).contains("totalsExactly");
        assertThat(DevPipelineService.owners(r1, List.of())).containsExactly(TaskPipelineService.Stage.ANSWER);

        BlueprintDto bb = bizBlueprint();
        assertThat(BizQuestionService.check(bb, bizQuestions()).ok()).isTrue(); // 문항 3개, 사례 3개
        var fewer = new BizQuestionsDto(bizQuestions().questions().subList(0, 1));
        assertThat(BizQuestionService.check(bb, fewer).ok()).isFalse(); // Q2·Q3 가 없다
        var four = new ArrayList<>(bizQuestions().questions());
        four.add(new BizQuestionsDto.Question("Q4", "Q4. 사례에 없는 문항", List.of()));
        var checkFour = BizQuestionService.check(bb, new BizQuestionsDto(four));
        assertThat(checkFour.detail()).contains("설계도에 없는 문항");
        assertThat(BizPipelineService.owners(checkFour)).containsExactly(TaskPipelineService.Stage.QUESTIONS);

        // 파이프라인의 검사 목록에 들어 있다
        StageAi ai = devAi();
        var run = devRun(ai);
        run.create(s -> {});
        assertThat(run.checks()).anyMatch(c -> c.name().equals(TaskPipelineService.CASES_CHECK) && c.ok());
        assertThat(TaskPromptService.answerDev(20)).contains("implementing only the blueprint cases").contains("null fallback values");
        assertThat(TaskPromptService.blueprint(false, 20, "깊이")).contains("cases:").contains("flaws.caughtBy is that case's name");
    }

    @Test
    @DisplayName("버그: CSV 행(\"1000,100,5000000\")은 칸마다 읽어 천 단위 쉼표로 오해하지 않고, 진짜 천 단위(1,050)와 날짜·쪽 숫자는 그대로 동작한다")
    void csvRowNumbersArePerCell() {
        assertThat(BizNumbersService.numbers("1000,100,5000000")).containsExactlyInAnyOrder("1000", "100", "5000000");
        assertThat(BizNumbersService.numbers("새봄,4월,1000,100,5000000")).contains("1000", "100", "5000000").doesNotContain("100500", "0");
        assertThat(BizNumbersService.numbers("처리 여력은 1,050건, 신청은 12,100,000원이었다.")).containsExactlyInAnyOrder("1050", "12100000");
        assertThat(BizNumbersService.numbers("앞 100,500 뒤")).containsExactly("100500");

        BlueprintDto b = bizBlueprint();
        var table = new BlueprintDto.Table("자료/예약 매출.csv", List.of("월", "예약", "건수", "매출"), List.of(List.of("6월", "1000", "100", "5000000")));
        BlueprintDto withTable = new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), b.facts(), b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), List.of(table), b.figures(), b.docs(), b.questionPlan(), b.cases());
        var echoed = List.of(new BizDraftDto.DocDraft("자료/검토 안내.pdf", List.of("검토 안내\n" + BIZ_F1 + "\n6월,1000,100,5000000\n표 요약: 1000,100,5000000")));
        assertThat(BizNumbersService.documentsCheck(withTable, echoed).ok()).as(BizNumbersService.documentsCheck(withTable, echoed).detail()).isTrue();
        var wrong = List.of(new BizDraftDto.DocDraft("자료/검토 안내.pdf", List.of("검토 안내\n6월,1000,100,5000001")));
        assertThat(BizNumbersService.documentsCheck(withTable, wrong).ok()).isFalse();
    }

    @Test
    @DisplayName("버그: 근거 쪽 번호 범위(\"1-2\")는 형식 검사를 통과하고 조립에서 \"1,2\" 로 정리된다. 없는 쪽 범위나 숫자가 아닌 쪽은 계속 실패")
    void pageRangesAreAccepted() {
        assertThat(TaskAssemblyService.normalizePages("1-2")).isEqualTo("1,2");
        assertThat(TaskAssemblyService.normalizePages("1~3")).isEqualTo("1,2,3");
        assertThat(TaskAssemblyService.normalizePages("2쪽")).isEqualTo("2");
        assertThat(TaskAssemblyService.normalizePages("1, 3")).isEqualTo("1,3");
        assertThat(TaskAssemblyService.normalizePages("")).isEqualTo("");
        assertThat(TaskAssemblyService.normalizePages("앞쪽")).isEqualTo("앞쪽"); // 못 읽으면 그대로(검사가 잡는다)

        BizDraftDto d = BizFixtures.good("pages");
        List<BizDraftDto.QuestionDraft> qs = new ArrayList<>(d.questions());
        for (String pages : List.of("1-2", "1~2", "1,2")) {
            var q0 = qs.get(0);
            List<BizDraftDto.Source> sources = q0.sources().stream().map(x -> new BizDraftDto.Source(x.path(), x.path().contains("설문 요약") ? pages : x.pages())).toList();
            var edited = BizFixtures.edit(d, b -> b.questions(List.of(new BizDraftDto.QuestionDraft(q0.type(), q0.content(), q0.choices(), q0.accepted(), q0.calc(), sources, q0.commonWrongAnswers()),
                    qs.get(1), qs.get(2), qs.get(3))));
            var sourcesCheck = BizCheckService.forDraft(new PdfService(), new BizFixtures.FakeSolver(), "gpt-6.1-terra", edited).stream()
                    .filter(c -> c.name().equals(BizCheckService.SOURCES)).findFirst().orElseThrow().run(edited);
            assertThat(sourcesCheck.ok()).as(pages + ": " + sourcesCheck.detail()).isTrue();
        }
        var bad = BizFixtures.edit(d, b -> {
            var q0 = d.questions().get(0);
            var sources = q0.sources().stream().map(x -> new BizDraftDto.Source(x.path(), x.path().contains("설문 요약") ? "1-9" : x.pages())).toList();
            return b.questions(List.of(new BizDraftDto.QuestionDraft(q0.type(), q0.content(), q0.choices(), q0.accepted(), q0.calc(), sources, q0.commonWrongAnswers()),
                    d.questions().get(1), d.questions().get(2), d.questions().get(3)));
        });
        var badCheck = BizCheckService.forDraft(new PdfService(), new BizFixtures.FakeSolver(), "gpt-6.1-terra", bad).stream()
                .filter(c -> c.name().equals(BizCheckService.SOURCES)).findFirst().orElseThrow().run(bad);
        assertThat(badCheck.ok()).isFalse();
        assertThat(badCheck.detail()).contains("없는 쪽입니다");

        // 조립: 저장될 쪽 번호가 정리된다
        BlueprintDto b = bizBlueprint();
        var q = new BizDraftDto.QuestionDraft(BizDraftDto.Type.SHORT, "Q", List.of(), List.of("25"), "1", List.of(new BizDraftDto.Source("자료/검토 안내.pdf", "1-2")));
        BizDraftDto assembled = TaskAssemblyService.biz(BIZ_FORM, b, bizWriting(""), List.of(q), new BizAnswerDto("## 필수 항목", List.of()));
        assertThat(assembled.questions().get(0).sources().get(0).pages()).isEqualTo("1,2");
    }

    // ── 설정 ─────────────────────────────

    @Test
    @DisplayName("단계별 모델·추론 강도는 직군마다 설정으로 바꾼다(기본: DEV·BIZ 생성 sol, 관문 Sonnet, 모두 medium)")
    void stageSettings() {
        assertThat(PROPS.stage(true, "BLUEPRINT").model()).isEqualTo("gpt-6.1-sol");
        assertThat(PROPS.stage(true, "WRITING").model()).isEqualTo("gpt-6.1-sol");
        assertThat(PROPS.stage(false, "BLUEPRINT").model()).isEqualTo("gpt-6.1-sol");
        assertThat(PROPS.stage(false, "ANSWER").effort()).isEqualTo("medium");
        assertThat(PROPS.staged()).isTrue();

        var custom = new TaskGenProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, "oneshot",
                new TaskGenProperties.Stages(new TaskGenProperties.Track(null, new TaskGenProperties.Stage("m-1", "low", 5000), null, null, null, null, null), null), "high");
        assertThat(custom.staged()).isFalse();
        assertThat(custom.stage(true, "WRITING")).isEqualTo(new TaskGenProperties.Stage("m-1", "low", 5000));
        assertThat(custom.stage(false, "WRITING").model()).isEqualTo("gpt-6.1-sol");
        assertThat(custom.stage(true, "CODE").effort()).isEqualTo("medium");
    }

    @Test
    @DisplayName("TaskGenRequest 는 기존 그대로: 폼 검사를 통과한 DEV 폼으로 단계별 생성이 돈다")
    void formStillValid() {
        assertThat(DEV_FORM.validated().track()).isEqualTo("DEV");
    }
}
