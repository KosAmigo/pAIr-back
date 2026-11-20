package com.pair.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pair.task.TestTasks;

/** 개발 과제 검증: 원본 코드는 숨은 필수 테스트에서 실패하고, 정답 코드는 모두 통과해야 한다. 컴파일 오류는 위치와 함께 알려야 한다. */
abstract class CodeRunnerContractTest {

    private CodeRunner runner;

    /** 검사할 구현. 모든 CodeRunner 가 같은 계약을 지켜야 한다. */
    abstract CodeRunner createRunner();

    @BeforeEach
    void setUp() {
        runner = createRunner();
    }

    private Map<String, String> srcPlusHidden(Map<String, String> src) {
        Map<String, String> all = new HashMap<>();
        src.forEach((k, v) -> {
            if (k.startsWith("src/")) all.put(k, v);
        });
        TestTasks.fixtureFiles("order-deadlock", "hidden").forEach((k, v) -> all.put("hidden/" + k, v));
        return all;
    }

    @Test
    void visibleTest_passesOnOriginalCode() {
        Map<String, String> files = TestTasks.fixtureFiles("order-deadlock", "files");
        CodeRunner.RunResult r = runner.run(files);
        r.lines().forEach(System.out::println);
        assertTrue(r.compiled());
        assertEquals(1, r.passed());
        assertEquals(0, r.failed());
    }

    @Test
    void hiddenRequiredTests_failOnOriginalCode() {
        CodeRunner.RunResult r = runner.run(srcPlusHidden(TestTasks.fixtureFiles("order-deadlock", "files")));
        r.tests().forEach(t -> System.out.println(t.passed() + " " + t.name()));
        assertTrue(r.compiled());
        assertFalse(r.tests().stream().filter(t -> t.name().contains("required_")).allMatch(CodeRunner.TestCase::passed));
    }

    @Test
    void hiddenTests_allPassOnSolution() throws IOException {
        Map<String, String> files = new HashMap<>(TestTasks.fixtureFiles("order-deadlock", "files"));
        try (InputStream in = getClass().getResourceAsStream("/solutions/order-deadlock/OrderService.java")) {
            files.put("src/shop/OrderService.java", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        CodeRunner.RunResult r = runner.run(srcPlusHidden(files));
        r.tests().forEach(t -> System.out.println(t.passed() + " " + t.name() + " " + t.message()));
        assertTrue(r.compiled());
        assertEquals(5, r.passed());
        assertEquals(0, r.failed());
    }

    @Test
    void compileError_reportsFileAndLine() {
        CodeRunner.BuildResult r = runner.build(Map.of("src/shop/Broken.java",
                "package shop;\npublic class Broken {\n    int x = \"문자열\";\n}\n"));
        assertFalse(r.compiled());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.file().endsWith("Broken.java") && d.line() == 3 && d.severity().equals("error")),
                r.diagnostics().toString());
    }
}
