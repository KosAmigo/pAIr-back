package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.pair.infra.sandbox.CodeRunner;

/** 테스트용 Draft 와 가짜 CodeRunner. 가짜 러너는 파일 내용의 표시(HiddenTest, FIXED)만 보고 결과를 꾸민다 */
final class TaskGenFixtures {

    private TaskGenFixtures() {}

    static TaskGenRequest form() {
        return new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 두 장을 겹치면 할인이 중복 적용", "SELF", 60);
    }

    static DevDraftDto.FileDraft f(String path, String content) {
        return new DevDraftDto.FileDraft(path, content);
    }

    /** 시작 코드: 줄이 여러 개라 정답이 12줄(추가+삭제) 넘게 바뀐다 */
    static final String CART_START = "package shop;\npublic class Cart {\n    int total() {\n        int sum = 0;\n        sum += 1;\n        sum += 2;\n"
            + "        sum += 3;\n        sum += 4;\n        sum += 5;\n        sum += 6;\n        return sum;\n    }\n}\n";

    static final String CART_SOLVED = "package shop;\npublic class Cart {\n    int total() {\n        int sum = 0;\n        sum += 11;\n        sum += 12;\n"
            + "        sum += 13;\n        sum += 14;\n        sum += 15;\n        sum += 16;\n        return sum; // FIXED\n    }\n}\n";

    static final String NOTES = "# 장애 접수 메모\n- 고객 A: 쿠폰 두 장을 쓴 주문의 결제 금액이 평소보다 훨씬 적게 나옴\n"
            + "- 고객 B: 같은 쿠폰 조합인데 어제는 정상이고 오늘은 금액이 이상함\n- 접수 로그: order=1042 coupons=2 total=3100 expected=6200\n"
            + "- 접수 로그: order=1043 coupons=1 total=6200 expected=6200\n- 고객센터 확인: 쿠폰이 한 장일 때는 문제 없음\n"
            + "- 재현 방법: 같은 장바구니에 쿠폰 두 장을 넣고 결제 금액을 조회하면 매번 같은 결과가 나옴\n";

    /** 가짜 러너가 통과시키는 정상 Draft (15개 검사 모두 통과) */
    static DevDraftDto good() {
        return new DevDraftDto("쿠폰 할인 오류", "쿠폰을 겹치면 할인이 두 번 들어갑니다.", "coupon-discount", "장애 분석",
                List.of("쿠폰 두 장을 쓰면 결제 금액이 이상합니다.", "원인을 찾아 고치세요."), List.of("금액을 올바르게 계산한다.", "테스트를 추가한다."),
                "# 정답\n쿠폰은 한 장만 적용한다.",
                List.of(f("src/shop/Cart.java", CART_START),
                        f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n"),
                        f("NOTES.md", NOTES)),
                List.of(f("test/shop/HiddenCartTest.java", "package shop;\nclass HiddenCartTest {\n    @Test\n    void couponOnce() {}\n}\n")),
                List.of(f("src/shop/Cart.java", CART_SOLVED)),
                null, null,
                List.of("중복 적용", "이중 할인"),
                List.of(new DevDraftDto.WrongFix("할인을 한 번만 빼지만 쿠폰 순서를 무시한다",
                                List.of(f("src/shop/Cart.java", CART_START.replace("sum += 1;", "sum += 7;")))),
                        new DevDraftDto.WrongFix("두 번째 쿠폰만 막아 세 장일 때 틀린다",
                                List.of(f("src/shop/Cart.java", CART_START.replace("sum += 2;", "sum += 8;"))))),
                "AI 가 고친 코드가 다른 쿠폰 조합에서도 맞는지 어떻게 확인했나요?",
                List.of(new DevDraftDto.AltSolution("합계를 식으로 한 번에 더한다(정답과 구조가 다름)",
                        List.of(f("src/shop/Cart.java", CART_SOLVED.replace("sum += 11;", "sum += 10 + 1;"))))),
                List.of(JUDGMENT), List.of(FLAW));
    }

    /** E2 판단 지점 예시: 근거 문장은 설명(description)에 그대로 있다 */
    static final DevDraftDto.JudgmentPoint JUDGMENT = new DevDraftDto.JudgmentPoint("쿠폰을 겹쳐 쓸 때 어떻게 처리할지",
            List.of("한 장만 적용", "모두 적용하되 상한을 둠"), "쿠폰 두 장을 쓰면 결제 금액이 이상합니다.",
            List.of("쿠폰 두 장을 쓰면 결제 금액이 이상합니다.", "금액을 올바르게 계산한다."));

    /** E3 숨은 결함 예시: 숨은 테스트 couponOnce 가 시작 코드에서 실패한다 */
    static final DevDraftDto.HiddenFlaw FLAW = new DevDraftDto.HiddenFlaw("쿠폰이 두 장일 때 할인이 두 번 들어가는데 공개 사례는 한 장뿐이다",
            "NOTES.md 의 접수 로그(order=1042)와 숨은 테스트 결과를 대조", "couponOnce");

    /** Draft 의 일부만 바꾼 복사본 */
    static DevDraftDto edit(DevDraftDto d, UnaryOperator<Dev> change) {
        return change.apply(new Dev(d)).build();
    }

    static final class Dev {
        String code, category, answer, taskFollowUp, title, summary;
        List<String> description, requirements, trapKeywords;
        List<DevDraftDto.FileDraft> files, hiddenTests, solution;
        List<DevDraftDto.WrongFix> wrongFixes;
        List<DevDraftDto.AltSolution> altSolutions;
        List<DevDraftDto.JudgmentPoint> judgmentPoints;
        List<DevDraftDto.HiddenFlaw> hiddenFlaws;
        String difficulty;
        List<String> competencies;

        Dev(DevDraftDto d) {
            title = d.title();
            summary = d.summary();
            code = d.code();
            category = d.category();
            description = d.description();
            requirements = d.requirements();
            answer = d.answer();
            files = d.files();
            hiddenTests = d.hiddenTests();
            solution = d.solution();
            difficulty = d.difficulty();
            competencies = d.competencies();
            trapKeywords = d.trapKeywords();
            wrongFixes = d.wrongFixes();
            taskFollowUp = d.taskFollowUp();
            altSolutions = d.altSolutions();
            judgmentPoints = d.judgmentPoints();
            hiddenFlaws = d.hiddenFlaws();
        }

        Dev code(String v) { code = v; return this; }
        Dev category(String v) { category = v; return this; }
        Dev description(List<String> v) { description = v; return this; }
        Dev requirements(List<String> v) { requirements = v; return this; }
        Dev files(List<DevDraftDto.FileDraft> v) { files = v; return this; }
        Dev hiddenTests(List<DevDraftDto.FileDraft> v) { hiddenTests = v; return this; }
        Dev solution(List<DevDraftDto.FileDraft> v) { solution = v; return this; }
        Dev trapKeywords(List<String> v) { trapKeywords = v; return this; }
        Dev wrongFixes(List<DevDraftDto.WrongFix> v) { wrongFixes = v; return this; }
        Dev altSolutions(List<DevDraftDto.AltSolution> v) { altSolutions = v; return this; }
        Dev judgmentPoints(List<DevDraftDto.JudgmentPoint> v) { judgmentPoints = v; return this; }
        Dev hiddenFlaws(List<DevDraftDto.HiddenFlaw> v) { hiddenFlaws = v; return this; }
        Dev taskFollowUp(String v) { taskFollowUp = v; return this; }
        Dev difficulty(String v) { difficulty = v; return this; }
        Dev competencies(List<String> v) { competencies = v; return this; }

        /** 시작 코드 파일(src/shop/Cart.java)만 바꾼다 */
        Dev filesWith(DevDraftDto.FileDraft extra) {
            List<DevDraftDto.FileDraft> out = new ArrayList<>(files);
            out.add(extra);
            files = out;
            return this;
        }

        DevDraftDto build() {
            return new DevDraftDto(title, summary, code, category, description, requirements, answer, files, hiddenTests, solution,
                    difficulty, competencies, trapKeywords, wrongFixes, taskFollowUp, altSolutions, judgmentPoints, hiddenFlaws);
        }
    }

    static DevDraftDto withSolution(DevDraftDto d, String content) {
        return edit(d, b -> b.solution(List.of(f(d.solution().get(0).path(), content))));
    }

    static DevDraftDto withFiles(DevDraftDto d, List<DevDraftDto.FileDraft> files) {
        return edit(d, b -> b.files(files));
    }

    /** 정답에 FIXED 표시가 없어 "정답은 전부 통과" 만 실패하는 Draft */
    static DevDraftDto badSolution() {
        return withSolution(good(), CART_SOLVED.replace(" // FIXED", ""));
    }

    /**
     * 숨은 테스트(파일 이름에 Hidden)가 붙으면: 정답(FIXED)이면 전부 통과, 아니면 일부 실패.
     * bugCaught=false 면 숨은 테스트가 시작 코드에서도 통과한다.
     */
    static final class FakeRunner implements CodeRunner {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean bugCaught = true;
        volatile long millis = 100;

        @Override
        public RunResult run(Map<String, String> sources) {
            calls.incrementAndGet();
            boolean hidden = sources.keySet().stream().anyMatch(k -> k.contains("Hidden"));
            boolean fixed = sources.values().stream().anyMatch(v -> v.contains("FIXED"));
            if (!hidden || fixed || !bugCaught) return new RunResult(true, List.of(), hidden ? 3 : 2, 0, List.of(), List.of(), millis);
            return new RunResult(true, List.of(), 1, 2, List.of(new TestCase("shop.HiddenCartTest.couponOnce", false, "x")), List.of(), millis);
        }

        @Override
        public BuildResult build(Map<String, String> sources) {
            return new BuildResult(true, List.of(), List.of(), millis);
        }
    }
}
