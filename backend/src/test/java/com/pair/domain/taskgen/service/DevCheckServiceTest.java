package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import static com.pair.domain.taskgen.service.TaskGenFixtures.CART_SOLVED;
import static com.pair.domain.taskgen.service.TaskGenFixtures.CART_START;
import static com.pair.domain.taskgen.service.TaskGenFixtures.NOTES;
import static com.pair.domain.taskgen.service.TaskGenFixtures.edit;
import static com.pair.domain.taskgen.service.TaskGenFixtures.f;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.PairProperties;
import com.pair.infra.sandbox.LocalJvmRunner;

/** 일부러 망가뜨린 Draft 에서 해당 검사만 실패하는지 */
@Tag("slow")
class DevCheckServiceTest {

    private final TaskGenFixtures.FakeRunner runner = new TaskGenFixtures.FakeRunner();

    private List<GenCheckResultDto> run(DevDraftDto d, TaskGenRequest form) {
        return DevCheckService.forDraft(runner, d, form).stream().map(c -> c.run(d)).toList();
    }

    private List<String> failedNames(DevDraftDto d) {
        return run(d, null).stream().filter(r -> !r.ok()).map(GenCheckResultDto::name).toList();
    }

    private GenCheckResultDto result(DevDraftDto d, String name, TaskGenRequest form) {
        return run(d, form).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static final List<DevDraftDto.FileDraft> VISIBLE = List.of(f("src/shop/Cart.java", CART_START),
            f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES));

    @Test
    @DisplayName("정상 Draft 는 검사 13개를 모두 통과하고, 같은 실행은 한 번만 돈다 (기본 3번 + 오답 2번 + 다른 풀이 1번)")
    void allPass() {
        var results = run(TaskGenFixtures.good(), TaskGenFixtures.form());
        assertThat(results).hasSize(13).allMatch(GenCheckResultDto::ok, results.toString());
        assertThat(results).extracting(GenCheckResultDto::level).containsOnly("fail", "warn");
        assertThat(results).filteredOn(r -> r.name().equals(DevCheckService.AMOUNT)).extracting(GenCheckResultDto::level).containsExactly("warn");
        assertThat(results).filteredOn(r -> !r.name().equals(DevCheckService.AMOUNT)).extracting(GenCheckResultDto::level).containsOnly("fail");
        assertThat(runner.calls.get()).isEqualTo(6);
    }

    @Test
    @DisplayName("숨은 테스트가 시작 코드에서 통과하면 '숨은 테스트가 버그를 잡음' 만 실패한다")
    void hiddenDoesNotCatch() {
        runner.bugCaught = false;
        // 숨은 테스트가 아무것도 못 잡으니 오답도 모두 통과한다 → 오답 검사도 함께 실패
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevCheckService.HIDDEN_CATCHES, DevCheckService.WRONG_FIXES, DevCheckService.HIDDEN_FLAW);
    }

    @Test
    @DisplayName("E2: 판단 지점이 비었거나, 선택지가 1개거나, 근거 문장이 응시자 자료에 없으면 '판단 지점과 근거가 자료에 있음' 만 실패한다")
    void judgmentPoints() {
        var good = TaskGenFixtures.good();
        assertThat(failedNames(edit(good, b -> b.judgmentPoints(List.of())))).containsExactly(DevCheckService.JUDGMENT);
        var oneOption = new DevDraftDto.JudgmentPoint("정할 것", List.of("한 가지"), "쿠폰 두 장을 쓰면 결제 금액이 이상합니다.");
        assertThat(failedNames(edit(good, b -> b.judgmentPoints(List.of(oneOption))))).containsExactly(DevCheckService.JUDGMENT);
        var noBasis = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), "이 문장은 어느 자료에도 없습니다 절대로");
        var r = result(edit(good, b -> b.judgmentPoints(List.of(noBasis))), DevCheckService.JUDGMENT, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.detail()).contains("근거 문장을 응시자 자료에서 찾지 못했습니다");
        // 숨은 테스트에만 있는 문장은 응시자에게 안 보이므로 근거가 될 수 없다
        var hiddenOnly = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), "class HiddenCartTest");
        assertThat(failedNames(edit(good, b -> b.judgmentPoints(List.of(hiddenOnly))))).containsExactly(DevCheckService.JUDGMENT);
        // 근거가 요구사항이나 NOTES.md 에 있어도 된다(공백·따옴표 차이 무시)
        var inNotes = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), "\"고객 B: 같은 쿠폰 조합인데   어제는 정상이고 오늘은 금액이 이상함\"", TaskGenFixtures.JUDGMENT.evidence());
        assertThat(failedNames(edit(good, b -> b.judgmentPoints(List.of(inNotes))))).isEmpty();
    }

    @Test
    @DisplayName("E2 M4: 선택지별 근거 사실이 없거나, 자료에 없거나, 자료가 있다고만 가리키면 '판단 지점과 근거가 자료에 있음' 이 fail 이다")
    void judgmentEvidence() {
        var good = TaskGenFixtures.good();
        String basis = TaskGenFixtures.JUDGMENT.basis();
        var none = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), basis, null);
        assertThat(result(edit(good, b -> b.judgmentPoints(List.of(none))), DevCheckService.JUDGMENT, null).detail()).contains("선택지별 근거 사실(evidence)이 없습니다");
        var few = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), basis, List.of(basis));
        assertThat(result(edit(good, b -> b.judgmentPoints(List.of(few))), DevCheckService.JUDGMENT, null).detail()).contains("근거가 없습니다");
        var absent = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), basis, List.of(basis, "운영 기록에 소비자 A 는 -1 을 받는다고 적혀 있습니다"));
        assertThat(result(edit(good, b -> b.judgmentPoints(List.of(absent))), DevCheckService.JUDGMENT, null).detail()).contains("근거 사실을 응시자 자료에서 찾지 못했습니다");
        // 사례가 있다고만 말하고 실제 사례가 없는 문장(설명에는 있지만 사실이 아니다)
        var pointer = new DevDraftDto.JudgmentPoint("정할 것", List.of("가", "나"), basis, List.of(basis, "운영 자료에 사례가 모두 있습니다"));
        var withPointer = edit(good, b -> b.judgmentPoints(List.of(pointer)).description(List.of("운영 자료에 사례가 모두 있습니다", basis)));
        var r = result(withPointer, DevCheckService.JUDGMENT, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.detail()).contains("가리킵니다");
    }

    @Test
    @DisplayName("옛 형식(judgmentPoints·hiddenFlaws 필드 없음)은 읽히고, 두 검사가 warn 으로만 알린다(다시 생성하지 않음)")
    void legacyDraftWithoutE2E3() {
        var legacy = edit(TaskGenFixtures.good(), b -> b.judgmentPoints(null).hiddenFlaws(null));
        var results = run(legacy, null);
        assertThat(results).filteredOn(r -> !r.ok()).extracting(GenCheckResultDto::name)
                .containsExactly(DevCheckService.JUDGMENT, DevCheckService.HIDDEN_FLAW);
        assertThat(results).filteredOn(r -> !r.ok()).allSatisfy(r -> {
            assertThat(r.level()).isEqualTo(GenCheckResultDto.WARN);
            assertThat(r.needsRegeneration()).isFalse();
        });
    }

    @Test
    @DisplayName("E3: 숨은 결함이 비었거나, 잡는 테스트가 숨은 테스트에 없거나, 어디서도 실패하지 않으면 '숨은 결함을 숨은 테스트가 잡음' 만 실패한다")
    void hiddenFlaws() {
        var good = TaskGenFixtures.good();
        assertThat(failedNames(edit(good, b -> b.hiddenFlaws(List.of())))).containsExactly(DevCheckService.HIDDEN_FLAW);
        var noTest = new DevDraftDto.HiddenFlaw("결함", "NOTES.md 대조", "noSuchTest");
        var r = result(edit(good, b -> b.hiddenFlaws(List.of(noTest))), DevCheckService.HIDDEN_FLAW, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo(GenCheckResultDto.FAIL);
        assertThat(r.detail()).contains("noSuchTest").contains("숨은 테스트 파일에 없습니다");
        var noVerify = new DevDraftDto.HiddenFlaw("결함", " ", "couponOnce");
        assertThat(failedNames(edit(good, b -> b.hiddenFlaws(List.of(noVerify))))).containsExactly(DevCheckService.HIDDEN_FLAW);
        // 메서드 이름에 ()를 붙여 적어도 된다
        var withParens = new DevDraftDto.HiddenFlaw("결함", "NOTES.md 대조", "couponOnce()");
        assertThat(failedNames(edit(good, b -> b.hiddenFlaws(List.of(withParens))))).isEmpty();
        // 그 테스트가 어디서도 실패하지 않으면(시작 코드, 오답 모두 통과) 결함을 잡지 못한다
        runner.bugCaught = false;
        var rr = result(good, DevCheckService.HIDDEN_FLAW, null);
        assertThat(rr.ok()).isFalse();
        assertThat(rr.detail()).contains("결함을 잡지 못합니다");
    }

    @Test
    @DisplayName("정답이 숨은 테스트에 실패하면 '정답은 전부 통과' 만 실패한다")
    void solutionFails() {
        assertThat(failedNames(TaskGenFixtures.badSolution())).containsExactly(DevCheckService.SOLVED_PASSES);
    }

    @Test
    @DisplayName("src 에 '// 버그' 주석이 있으면 '버그 힌트 주석 없음' 만 실패한다")
    void hintComment() {
        DevDraftDto d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/shop/Cart.java", CART_START.replace("int sum = 0;", "int sum = 0; // 버그: 여기")),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"), f("NOTES.md", NOTES)));
        assertThat(failedNames(d)).containsExactly(DevCheckService.NO_HINT);
    }

    @Test
    @DisplayName("경로 형식이 틀리면 형식이 실패하고 코드는 실행하지 않는다")
    void badPath() {
        DevDraftDto d = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(
                f("src/../../evil/Cart.java", "package shop;\nclass Cart {}\n"),
                f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")));
        assertThat(failedNames(d)).contains(DevCheckService.FORMAT).doesNotContain(DevCheckService.NO_HINT);
        assertThat(runner.calls.get()).isZero();
    }

    @Test
    @DisplayName("공개 테스트가 없거나 숨은 테스트가 test/ 밖이면 형식이 실패한다. NOTES.md·logs/ 는 자료로 허용, 다른 경로는 안 됨")
    void formatRules() {
        DevDraftDto noPublicTest = TaskGenFixtures.withFiles(TaskGenFixtures.good(), List.of(f("src/shop/Cart.java", "package shop;\nclass Cart {}\n")));
        assertThat(DevRunService.formatProblem(noPublicTest)).contains("공개 테스트");
        DevDraftDto hiddenOutside = edit(TaskGenFixtures.good(), b -> b.hiddenTests(List.of(f("src/shop/Hidden.java", "package shop;\nclass Hidden {}\n"))));
        assertThat(DevRunService.formatProblem(hiddenOutside)).contains("test/");

        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("logs/app.log", "x"))))).isNull();
        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("../evil.md", "x"))))).contains("경로 형식");
        assertThat(DevRunService.formatProblem(edit(TaskGenFixtures.good(), b -> b.filesWith(f("logs/../x.log", "x"))))).contains("경로 형식");
    }

    @Test
    @DisplayName("실행이 30초를 넘으면 시간 검사만 실패한다")
    void slow() {
        runner.millis = 31_000;
        assertThat(failedNames(TaskGenFixtures.good())).containsExactly(DevCheckService.TIME);
    }

    // ── 1. 함정 핵심어 노출 ─────────────────────

    // ── 2. 그럴듯한 오답 ─────────────────────

    @Test
    @DisplayName("오답이 숨은 테스트를 전부 통과하면 '그럴듯한 오답' 만 실패하고, 어떤 오답인지 적는다")
    void wrongFixPasses() {
        var passing = new DevDraftDto.WrongFix("날짜만 바꾼 풀이", List.of(f("src/shop/Cart.java", CART_SOLVED))); // FIXED 표시 = 가짜 러너가 모두 통과시킴
        var d = edit(TaskGenFixtures.good(), b -> b.wrongFixes(List.of(TaskGenFixtures.good().wrongFixes().get(0), passing)));
        assertThat(failedNames(d)).containsExactly(DevCheckService.WRONG_FIXES);
        String detail = result(d, DevCheckService.WRONG_FIXES, null).detail();
        assertThat(detail).contains("오답 2").contains("날짜만 바꾼 풀이").contains("숨은 테스트를 모두 통과").doesNotContain("오답 1");
    }

    @Test
    @DisplayName("오답 2~3개가 모두 숨은 테스트에서 실패하면 통과, 1개나 4개거나 이유·경로가 이상하면 실패")
    void wrongFixRules() {
        var good = TaskGenFixtures.good();
        assertThat(result(good, DevCheckService.WRONG_FIXES, null).ok()).isTrue();
        var one = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0))));
        assertThat(result(one, DevCheckService.WRONG_FIXES, null).detail()).contains("2~3개");
        assertThat(result(edit(good, b -> b.wrongFixes(null)), DevCheckService.WRONG_FIXES, null).ok()).isFalse();
        var four = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0), good.wrongFixes().get(1), good.wrongFixes().get(0), good.wrongFixes().get(1))));
        assertThat(result(four, DevCheckService.WRONG_FIXES, null).detail()).contains("3개까지");
        var noReason = edit(good, b -> b.wrongFixes(List.of(new DevDraftDto.WrongFix(" ", good.wrongFixes().get(0).files()), good.wrongFixes().get(1))));
        assertThat(result(noReason, DevCheckService.WRONG_FIXES, null).detail()).contains("reason");
        var testPath = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0),
                new DevDraftDto.WrongFix("x", List.of(f("test/shop/HiddenCartTest.java", "class X {}"))))));
        assertThat(result(testPath, DevCheckService.WRONG_FIXES, null).detail()).contains("src/");
        var traversal = edit(good, b -> b.wrongFixes(List.of(good.wrongFixes().get(0),
                new DevDraftDto.WrongFix("x", List.of(f("src/../../evil/A.java", "class X {}"))))));
        assertThat(result(traversal, DevCheckService.WRONG_FIXES, null).detail()).contains("경로");
    }

    // ── 3. 분량 대비 시간 (warn) ─────────────────────

    @Test
    @DisplayName("정답 변경 줄 수(추가+삭제)를 센다: 한 줄 고치면 2줄, 빈 줄·앞뒤 공백은 무시")
    void changedLines() {
        assertThat(DevCheckService.changedLines(CART_START, CART_START)).isZero();
        assertThat(DevCheckService.changedLines(CART_START, CART_START.replace("sum += 1;", "sum += 9;"))).isEqualTo(2);
        assertThat(DevCheckService.changedLines("a\nb\n", "a\n\n  b  \n")).isZero();
        assertThat(DevCheckService.changedLines(null, "a\nb\n")).isEqualTo(2);
        assertThat(DevCheckService.changedLines("a\nb\nc\n", "a\nc\n")).isEqualTo(1);
    }

    @Test
    @DisplayName("정답 변경이 시간별 최소치(20분 2줄, 40분 6줄, 60분 12줄)보다 적으면 warn 으로 알리고, 이름만 실패 목록에 들어간다")
    void amountWarning() {
        var small = TaskGenFixtures.withSolution(TaskGenFixtures.good(), CART_START.replace("sum += 1;", "sum += 9; // FIXED"));
        var r60 = result(small, DevCheckService.AMOUNT, TaskGenFixtures.form());
        assertThat(r60.ok()).isFalse();
        assertThat(r60.level()).isEqualTo("warn");
        assertThat(r60.needsRegeneration()).isFalse();
        assertThat(r60.detail()).isEqualTo("정답 변경 2줄, 60분 과제치고 적음. 난이도·시간을 낮추거나 자료를 보강하세요");

        var form40 = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 중복 적용", "SELF", 40);
        var form20 = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 중복 적용", "SELF", 20);
        assertThat(result(small, DevCheckService.AMOUNT, form40).detail()).contains("2줄, 40분");
        assertThat(result(small, DevCheckService.AMOUNT, form20).ok()).isTrue(); // 20분은 2줄이면 충분

        var six = TaskGenFixtures.withSolution(TaskGenFixtures.good(), CART_START.replace("sum += 1;", "sum += 9;").replace("sum += 2;", "sum += 9;")
                .replace("sum += 3;", "sum += 9; // FIXED"));
        assertThat(result(six, DevCheckService.AMOUNT, form40).ok()).isTrue(); // 6줄
        assertThat(result(six, DevCheckService.AMOUNT, TaskGenFixtures.form()).ok()).isFalse(); // 60분은 12줄 필요
        // warn 이 있어도 fail 검사는 모두 통과한다
        assertThat(run(small, TaskGenFixtures.form()).stream().filter(GenCheckResultDto::needsRegeneration)).isEmpty();
    }

    // ── 4. 과제 종류별 필수 자료 ─────────────────────

    // ── 5. 후속 질문 ─────────────────────

    private static DevDraftDto.AltSolution alt(String note, String cart) {
        return new DevDraftDto.AltSolution(note, List.of(f("src/shop/Cart.java", cart)));
    }

    @Test
    @DisplayName("다른 풀이가 하나라도 숨은 테스트에 떨어지면 fail: 어떤 테스트가 어떤 풀이를 떨어뜨렸는지 적고 재생성 대상이다")
    void altSolutionFails() {
        var good = TaskGenFixtures.good();
        var d = edit(good, b -> b.altSolutions(List.of(
                alt("락 안에서 조회", CART_SOLVED.replace("sum += 11;", "sum += 10 + 1;")),
                alt("락 밖에서 조회", CART_SOLVED.replace(" // FIXED", "").replace("sum += 12;", "sum += 10 + 2;")))));
        assertThat(failedNames(d)).containsExactly(DevCheckService.ALT_SOLUTIONS);
        var r = result(d, DevCheckService.ALT_SOLUTIONS, null);
        assertThat(r.needsRegeneration()).isTrue();
        assertThat(r.level()).isEqualTo("fail");
        assertThat(r.detail()).contains("shop.HiddenCartTest.couponOnce").contains("다른 풀이 2 (락 밖에서 조회)")
                .doesNotContain("다른 풀이 1 (락 안에서 조회) 을 떨어뜨렸습니다").contains("대기 장치").contains("결과");
    }

    @Test
    @DisplayName("다른 풀이가 모두 통과하면 통과, 컴파일 안 되거나 경로가 src/ 가 아니면 fail")
    void altSolutionPassesOrBroken() {
        var good = TaskGenFixtures.good();
        var r = result(good, DevCheckService.ALT_SOLUTIONS, null);
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).contains("1개 모두");

        var badPath = edit(good, b -> b.altSolutions(List.of(new DevDraftDto.AltSolution("경로 이상",
                List.of(f("test/shop/HiddenCartTest.java", CART_SOLVED))))));
        assertThat(result(badPath, DevCheckService.ALT_SOLUTIONS, null).needsRegeneration()).isTrue();
        assertThat(result(badPath, DevCheckService.ALT_SOLUTIONS, null).detail()).contains("다른 풀이 1의 파일 경로");

        var empty = edit(good, b -> b.altSolutions(List.of(new DevDraftDto.AltSolution("파일 없음", List.of()))));
        assertThat(result(empty, DevCheckService.ALT_SOLUTIONS, null).ok()).isFalse();
    }

    @Test
    @DisplayName("다른 풀이가 없으면(옛 형식) warn 이라 재생성하지 않고, 정답과 똑같은 풀이도 warn")
    void altSolutionMissingIsWarn() {
        var none = edit(TaskGenFixtures.good(), b -> b.altSolutions(null));
        var r = result(none, DevCheckService.ALT_SOLUTIONS, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.level()).isEqualTo("warn");
        assertThat(r.needsRegeneration()).isFalse();
        assertThat(r.detail()).contains("altSolutions");
        assertThat(result(edit(none, b -> b.altSolutions(List.of())), DevCheckService.ALT_SOLUTIONS, null).level()).isEqualTo("warn");

        var same = edit(none, b -> b.altSolutions(List.of(alt("똑같음", CART_SOLVED.replace("    int", "      int")))));
        var rs = result(same, DevCheckService.ALT_SOLUTIONS, null);
        assertThat(rs.level()).isEqualTo("warn");
        assertThat(rs.detail()).contains("정답과 같아");
    }

    @Test
    @DisplayName("다른 풀이는 앞의 2개만 돌린다")
    void altSolutionMaxTwo() {
        var many = edit(TaskGenFixtures.good(), b -> b.altSolutions(List.of(
                alt("1", CART_SOLVED.replace("sum += 11;", "sum += 10 + 1;")), alt("2", CART_SOLVED.replace("sum += 12;", "sum += 10 + 2;")),
                alt("3(돌리지 않음)", CART_SOLVED.replace(" // FIXED", "")))));
        assertThat(failedNames(many)).isEmpty();
        assertThat(runner.calls.get()).isEqualTo(7); // 기본 3 + 오답 2 + 다른 풀이 2
    }

    @Test
    @DisplayName("해법 문장 패턴: 실제 예시 문장 몇 개로 오탐·미탐을 조정한다")
    void solutionPatterns() {
        for (String hit : List.of("재고 차감과 확인을 한 번에 확정해야 합니다.", "락을 하나로 합치도록 고치면 해결됩니다.", "상태를 먼저 바꿔야 한다.",
                "금액에서 차감액을 뺀 뒤 100으로 나눈다.", "합계를 곱해 내림한다.", "점수는 결제액의 100분의 1이며 가장 큰 정수를 쓴다.",
                // M5: 리뷰에서 나온 누출 문장(규칙 문장, 결함을 피하는 결과를 규칙으로 말함)
                "한 구간의 남는 처리 여력은 다른 구간의 접수 건수를 처리한 실적으로 잡히지 않는다.",
                "입고 결과가 이 범위를 넘는 요청은 IllegalArgumentException이 발생하며 재고는 바뀌지 않습니다.")) {
            assertThat(DevCheckService.NOTES_HINT.matcher(hit).find() || DevCheckService.REQ_HINT.matcher(hit).find()).as(hit).isTrue();
        }
        for (String miss : List.of("상품 A의 수량이 1인 상태에서 서로 다른 주문 두 건이 접수됐고 조회값은 -1이었습니다.",
                "같은 주문번호를 다시 보내도 잔여 수량은 그대로 유지된다.", "승인 개수는 재고 수량을 넘지 않는다.", "내림차순으로 정렬된 목록을 반환한다.",
                "구매 금액 101원, 차감액 2원이면 5점이 기대된다.", "담당자는 재전송이 새 주문으로 취급되지 않는지 확인해 달라고 요청했습니다.")) {
            assertThat(DevCheckService.NOTES_HINT.matcher(miss).find() || DevCheckService.REQ_HINT.matcher(miss).find()).as(miss).isFalse();
        }
    }

    @Test
    @DisplayName("단서 문장: 해법 문장이거나 AI 풀이가 바꾼 줄의 식별자가 든 문장만 고르고 최대 5개")
    void clueSentences() {
        var d = edit(TaskGenFixtures.good(), b -> b.requirements(List.of("available() 는 항상 0 이상을 돌려준다.", "금액을 올바르게 계산한다.",
                "락을 하나로 합치도록 고친다.")).files(List.of(f("src/shop/Cart.java", CART_START), f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                f("NOTES.md", NOTES + "한 번에 확정해야 합니다."))));
        var clues = DevCheckService.clueSentences(d, "src/shop/Cart.java -synchronized void reserve() +available()");
        assertThat(clues).contains("[요구사항] available() 는 항상 0 이상을 돌려준다.", "[요구사항] 락을 하나로 합치도록 고친다.", "[NOTES.md] 한 번에 확정해야 합니다.")
                .doesNotContain("[요구사항] 금액을 올바르게 계산한다.");
        assertThat(clues.size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("맞춤 후속 질문이 비었거나 핵심어·숨은 테스트 이름이 있으면 '후속 질문이 과제에 맞음' 만 실패한다")
    void followUp() {
        var d = TaskGenFixtures.good();
        assertThat(failedNames(edit(d, b -> b.taskFollowUp(" ")))).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(failedNames(edit(d, b -> b.taskFollowUp(null)))).containsExactly(DevCheckService.FOLLOW_UP);

        var withName = edit(d, b -> b.taskFollowUp("couponOnce 테스트가 통과한 이유를 설명해 주세요."));
        assertThat(failedNames(withName)).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(result(withName, DevCheckService.FOLLOW_UP, null).detail()).contains("couponOnce");

        var withKeyword = edit(d, b -> b.taskFollowUp("이중할인 문제를 어떻게 확인했나요?"));
        assertThat(failedNames(withKeyword)).containsExactly(DevCheckService.FOLLOW_UP);
        assertThat(result(withKeyword, DevCheckService.FOLLOW_UP, null).detail()).contains("이중 할인");
    }

    @Test
    @DisplayName("실제 LocalJvmRunner 로 작은 Java 예제를 끝까지 확인한다 (정상 → fail 검사 모두 통과, 정답이 틀리면 정답 검사만 실패)")
    void realRunner() {
        var local = new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
        String test = "package calc;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nimport org.junit.jupiter.api.Test;\n";
        DevDraftDto d = new DevDraftDto("절댓값 오류", "절댓값 계산이 음수에서 틀립니다.", "abs-bug", "기능 구현",
                List.of("음수 입력에서 결과가 틀립니다."), List.of("고친다."), "# 정답\n음수면 부호를 바꾼다.",
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n"),
                        f("test/calc/CalcTest.java", test + "class CalcTest { @Test void positive() { assertEquals(3, Calc.abs(3)); } }\n")),
                List.of(f("test/calc/HiddenCalcTest.java", test + "class HiddenCalcTest { @Test void negative() { assertEquals(3, Calc.abs(-3)); } }\n")),
                List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n; } }\n")),
                null, null, List.of("부호 반전", "마이너스"),
                List.of(new DevDraftDto.WrongFix("음수를 0 으로 바꾼다", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? 0 : n; } }\n"))),
                        new DevDraftDto.WrongFix("음수에 1 을 더한다", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? n + 1 : n; } }\n")))),
                "AI 가 고친 코드를 다른 입력으로도 확인했나요?",
                List.of(new DevDraftDto.AltSolution("Math.abs 로 바로 구한다",
                        List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return Math.abs(n); } }\n")))),
                List.of(new DevDraftDto.JudgmentPoint("0 의 부호를 어떻게 표시할지", List.of("0", "-0"), "음수 입력에서 결과가 틀립니다.",
                        List.of("음수 입력에서 결과가 틀립니다.", "고친다."))),
                List.of(new DevDraftDto.HiddenFlaw("음수 입력을 그대로 돌려줌", "공개 테스트는 양수만 확인하므로 숨은 테스트와 대조", "negative")));
        var results = DevCheckService.forDraft(local, d).stream().map(c -> c.run(d)).toList();
        assertThat(results.stream().filter(GenCheckResultDto::needsRegeneration)).isEmpty();
        assertThat(results.stream().filter(r -> !r.ok()).map(GenCheckResultDto::name)).containsExactly(DevCheckService.AMOUNT); // 분량 경고만

        DevDraftDto wrongSolution = TaskGenFixtures.withSolution(d, "package calc;\npublic class Calc { public static int abs(int n) { return n; } }\n");
        assertThat(DevCheckService.forDraft(local, wrongSolution).stream().map(c -> c.run(wrongSolution)).filter(GenCheckResultDto::needsRegeneration).map(GenCheckResultDto::name))
                .containsExactly(DevCheckService.SOLVED_PASSES);

        // 공개 테스트까지 깨지는 오답은 '숨은 테스트가 거른 것'이 아니므로 실패
        DevDraftDto publicOnly = edit(d, b -> b.wrongFixes(List.of(d.wrongFixes().get(0),
                new DevDraftDto.WrongFix("양수가 틀림", List.of(f("src/calc/Calc.java", "package calc;\npublic class Calc { public static int abs(int n) { return n < 0 ? -n : n + 1; } }\n"))))));
        var r = DevCheckService.forDraft(local, publicOnly).stream().map(c -> c.run(publicOnly)).filter(x -> x.name().equals(DevCheckService.WRONG_FIXES)).findFirst().orElseThrow();
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("오답 2").contains("공개 테스트에서만");
    }
}
