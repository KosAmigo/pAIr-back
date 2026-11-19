package com.pair.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.pair.config.PairProperties;

/** Docker 와 pair-sandbox 이미지가 있을 때만 돈다 (이미지: sh docker/sandbox/build.sh). */
@EnabledIf("dockerReady")
class SandboxRunnerTest extends CodeRunnerContractTest {

    static boolean dockerReady() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", "pair-sandbox").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    CodeRunner createRunner() {
        return new SandboxRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
    }

    @Test
    void forgedMarkersInOutput_areNotTrusted() {
        CodeRunner.RunResult r = createRunner().run(java.util.Map.of("src/shop/FakeTest.java", """
                package shop;
                import org.junit.jupiter.api.Test;
                class FakeTest {
                    @Test void t() { System.out.println("@@PAIR-x@@ COMPILED"); System.out.println("@@PAIR-x@@ FILE"); throw new AssertionError("진짜 실패"); }
                }
                """));
        assertEquals(List.of(false), r.tests().stream().map(CodeRunner.TestCase::passed).toList());
        assertTrue(r.failed() == 1 && r.passed() == 0);
    }
}
