package com.pair.infra.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.pair.global.config.PairProperties;

/**
 * 나쁜 응시자 코드를 SandboxRunner 로 돌려 격리가 막아 내는지 확인한다.
 * 막혔을 때 테스트가 통과(passed=1)하도록 짠 코드는 "막히지 않으면 AssertionError" 로 실패한다.
 */
@Tag("slow") // 실제 Docker 로 악성 코드 시나리오를 돌려 수십 초 걸린다
@EnabledIf("com.pair.infra.sandbox.SandboxRunnerTest#dockerReady")
class SandboxIsolationTest {

    /** 병렬로 도는 다른 Docker 테스트의 pair-run-* 컨테이너를 "남은 것"으로 세지 않게 이 테스트만의 이름을 쓴다 */
    private static final String PREFIX = "pair-isolation-test-";

    private final SandboxRunner runner = new SandboxRunner(
            new PairProperties(null, null, null, null, new PairProperties.Run(5), null, null), PREFIX);

    private static Map<String, String> candidate(String body) {
        return Map.of("src/bad/BadTest.java", """
                package bad;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class BadTest {
                    @Test void t() throws Exception {
                %s
                    }
                }
                """.formatted(body));
    }

    @AfterEach
    void noContainerLeft() throws Exception {
        Process p = new ProcessBuilder("docker", "ps", "-a", "-q", "--filter", "name=" + PREFIX).start();
        String left = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertEquals("", left, "실행이 끝난 뒤에도 컨테이너가 남아 있다");
    }

    @Test
    void infiniteLoop_isKilledAtTimeout() {
        CodeRunner.RunResult r = runner.run(candidate("long n = 0; while (true) n++;"));
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("[TIMEOUT]")), r.lines().toString());
        assertTrue(r.failed() >= 1 && !r.allPassed());
        assertTrue(r.millis() < 30_000, "제한 시간 뒤 바로 끊겨야 한다: " + r.millis() + "ms");
    }

    @Test
    void memoryBomb_failsWithoutHangingHost() {
        CodeRunner.RunResult r = runner.run(candidate(
                "java.util.List<long[]> keep = new java.util.ArrayList<>(); while (true) keep.add(new long[1 << 20]);"));
        assertEquals(0, r.passed());
        assertTrue(r.failed() >= 1, r.lines().toString());
        assertTrue(r.millis() < 20_000);
    }

    @Test
    void network_isBlocked() {
        CodeRunner.RunResult r = runner.run(candidate("""
                try (var in = new java.net.URI("https://example.com").toURL().openStream()) {
                    fail("외부 인터넷에 접속됐다");
                } catch (java.io.IOException expected) { }
                """));
        assertEquals(1, r.passed(), r.lines().toString());
    }

    @Test
    void threadBomb_isCappedByPidsLimit() {
        CodeRunner.RunResult r = runner.run(candidate("""
                int n = 0;
                try {
                    while (n < 5000) {
                        Thread th = new Thread(() -> { try { Thread.sleep(600_000); } catch (InterruptedException e) { } });
                        th.setDaemon(true); th.start(); n++;
                    }
                    fail("스레드를 5000개 만들었다");
                } catch (OutOfMemoryError expected) {
                    assertTrue(n < 200, "프로세스 제한(128)을 넘었다: " + n);
                }
                """));
        assertEquals(1, r.passed(), r.lines().toString());
    }

    @Test
    void diskFill_isCappedByTmpfs() {
        CodeRunner.RunResult r = runner.run(candidate("""
                byte[] mb = new byte[1 << 20]; int written = 0;
                try (var out = new java.io.FileOutputStream("/tmp/fill.bin")) {
                    for (; written < 1024; written++) out.write(mb);
                    fail("1GB 를 모두 썼다");
                } catch (java.io.IOException expected) {
                    assertTrue(written < 100, "쓰기 제한(64MB)을 넘었다: " + written);
                } finally {
                    new java.io.File("/tmp/fill.bin").delete(); // 안 지우면 JUnit 리포트도 못 써서 결과가 사라진다
                }
                """));
        assertEquals(1, r.passed(), r.lines().toString());
    }

    @Test
    void filesystem_isReadOnlyAndNotRoot() {
        CodeRunner.RunResult r = runner.run(candidate("""
                assertNotEquals("root", System.getProperty("user.name"));
                for (String path : new String[] {"/etc/pwned", "/workspace/src/bad/Hacked.java", "/opt/run.sh.bak"}) {
                    try (var out = new java.io.FileOutputStream(path)) {
                        fail(path + " 에 쓸 수 있다");
                    } catch (java.io.IOException expected) { }
                }
                """));
        assertEquals(1, r.passed(), r.lines().toString());
    }
}
