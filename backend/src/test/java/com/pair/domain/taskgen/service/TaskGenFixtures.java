package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.TaskGenForm;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pair.infra.sandbox.CodeRunner;

/** 테스트용 Draft 와 가짜 CodeRunner. 가짜 러너는 파일 내용의 표시(HiddenTest, FIXED)만 보고 결과를 꾸민다 */
final class TaskGenFixtures {

    private TaskGenFixtures() {}

    static TaskGenForm form() {
        return new TaskGenForm("DEV", "쿠폰 할인 계산", "쿠폰 두 장을 겹치면 할인이 중복 적용", "SELF", 60);
    }

    static DevDraft.FileDraft f(String path, String content) {
        return new DevDraft.FileDraft(path, content);
    }

    /** 가짜 러너를 통과하는 정상 Draft */
    static DevDraft good() {
        return new DevDraft("쿠폰 할인 오류", "쿠폰을 겹치면 할인이 두 번 들어갑니다.", "coupon-discount", "장애 분석",
                List.of("쿠폰 두 장을 쓰면 할인이 중복됩니다.", "원인을 찾아 고치세요."), List.of("중복 할인을 막는다.", "테스트를 추가한다."),
                "# 정답\n쿠폰은 한 장만 적용한다.",
                List.of(f("src/shop/Cart.java", "package shop;\npublic class Cart { int total() { return 1; } }\n"),
                        f("test/shop/CartTest.java", "package shop;\nclass CartTest {}\n")),
                List.of(f("test/shop/HiddenCartTest.java", "package shop;\nclass HiddenCartTest {}\n")),
                List.of(f("src/shop/Cart.java", "package shop;\npublic class Cart { int total() { return 2; } } // FIXED\n")));
    }

    static DevDraft withSolution(DevDraft d, String content) {
        return new DevDraft(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(), d.answer(),
                d.files(), d.hiddenTests(), List.of(f(d.solution().get(0).path(), content)));
    }

    static DevDraft withFiles(DevDraft d, List<DevDraft.FileDraft> files) {
        return new DevDraft(d.title(), d.summary(), d.code(), d.category(), d.description(), d.requirements(), d.answer(),
                files, d.hiddenTests(), d.solution());
    }

    /** 정답에 FIXED 표시가 없어 "정답은 전부 통과" 만 실패하는 Draft */
    static DevDraft badSolution() {
        return withSolution(good(), "package shop;\npublic class Cart { int total() { return 1; } }\n");
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
            return new RunResult(true, List.of(), 1, 2, List.of(new TestCase("coupon_once", false, "x")), List.of(), millis);
        }

        @Override
        public BuildResult build(Map<String, String> sources) {
            return new BuildResult(true, List.of(), List.of(), millis);
        }
    }
}
