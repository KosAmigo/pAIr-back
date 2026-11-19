package com.pair.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.pair.config.PairProperties;

/** 동시 실행 상한과 대기열: 상한을 넘기지 않고, 줄이 너무 길면 거절한다. */
@EnabledIf("com.pair.run.SandboxRunnerTest#dockerReady")
class SandboxConcurrencyLimitTest {

    private static SandboxRunner runner(int timeout, int max, int queueWait) {
        return new SandboxRunner(new PairProperties(null, null, null, null,
                new PairProperties.Run(timeout, null, null, max, queueWait), null, null));
    }

    private static int runningContainers() throws Exception {
        Process p = new ProcessBuilder("docker", "ps", "-q", "--filter", "name=pair-run-").start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out.isEmpty() ? 0 : out.split("\\s+").length;
    }

    private static final Map<String, String> OK = Map.of("src/a/A.java", "package a; public class A {}");

    @Test
    void neverRunsMoreContainersThanTheLimit_andAllRequestsFinish() throws Exception {
        SandboxRunner runner = runner(30, 2, 120);
        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        Thread watcher = Thread.ofVirtual().start(() -> {
            try {
                while (!done.get()) {
                    peak.accumulateAndGet(runningContainers(), Math::max);
                    Thread.sleep(100);
                }
            } catch (Exception ignored) { }
        });
        List<CodeRunner.BuildResult> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CodeRunner.BuildResult>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) fs.add(pool.submit((Callable<CodeRunner.BuildResult>) () -> runner.build(OK)));
            for (var f : fs) results.add(f.get());
        }
        done.set(true);
        watcher.join();
        assertEquals(6, results.stream().filter(CodeRunner.BuildResult::compiled).count());
        assertTrue(peak.get() >= 1 && peak.get() <= 2, "동시에 떠 있던 컨테이너 최대 " + peak.get());
    }

    @Test
    void rejectsWhenQueueWaitIsExceeded() throws Exception {
        SandboxRunner runner = runner(30, 1, 1);
        Map<String, String> slow = Map.of("src/s/SlowTest.java", """
                package s;
                import org.junit.jupiter.api.Test;
                class SlowTest { @Test void t() throws Exception { Thread.sleep(6000); } }
                """);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CodeRunner.RunResult> first = pool.submit(() -> runner.run(slow));
            Thread.sleep(2000); // 첫 요청이 자리를 잡을 때까지
            assertThrows(SandboxRunner.BusyException.class, () -> runner.build(OK));
            assertTrue(first.get().passed() == 1, "자리를 잡은 요청은 영향받지 않아야 한다");
        }
        assertEquals(0, runningContainers());
        // 자리가 반납됐으니 다시 실행된다
        assertTrue(runner.build(OK).compiled());
    }
}
