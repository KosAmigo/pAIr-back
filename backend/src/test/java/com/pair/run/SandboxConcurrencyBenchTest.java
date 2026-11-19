package com.pair.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pair.config.PairProperties;
import com.pair.task.TaskRepository;

/**
 * 동시에 N개를 돌렸을 때 걸리는 시간과 시간 초과 여부를 잰다 (빌드에서는 건너뜀).
 * 실행: PAIR_BENCH=1 [PAIR_BENCH_LEVELS=1,4,8] sh gradlew test --tests '*SandboxConcurrencyBenchTest'
 * 결과 표는 build/sandbox-bench.txt 에 남는다.
 */
@EnabledIf("com.pair.run.SandboxRunnerTest#dockerReady")
@EnabledIfEnvironmentVariable(named = "PAIR_BENCH", matches = ".+")
class SandboxConcurrencyBenchTest {

    private final SandboxRunner runner = new SandboxRunner(new PairProperties(null, null, null, null, new PairProperties.Run(30), null, null));

    @Test
    void measure() throws Exception {
        TaskRepository tasks = new TaskRepository(JsonMapper.builder().findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
        Map<String, String> files = new HashMap<>();
        tasks.visibleFiles("order-deadlock").forEach((k, v) -> { if (k.startsWith("src/")) files.put(k, v); });
        tasks.hiddenFiles("order-deadlock").forEach((k, v) -> files.put("hidden/" + k, v));
        try (InputStream in = getClass().getResourceAsStream("/solutions/order-deadlock/OrderService.java")) {
            files.put("src/shop/OrderService.java", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        StringBuilder table = new StringBuilder();
        table.append("호스트 코어 ").append(Runtime.getRuntime().availableProcessors())
                .append(", Docker 코어 ").append(dockerCpus()).append(", 실행당 제한 CPU 1 / 메모리 512MB / 제한시간 30초\n");
        table.append(String.format("%-6s %-10s %-10s %-10s %-8s %-8s%n", "동시", "전체(ms)", "평균(ms)", "최대(ms)", "성공", "실패/시간초과"));
        runner.run(files); // 이미지 캐시 예열
        for (String level : System.getenv().getOrDefault("PAIR_BENCH_LEVELS", "1,4,8").split(",")) {
            int n = Integer.parseInt(level.trim());
            long t0 = System.nanoTime();
            List<CodeRunner.RunResult> results = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<CodeRunner.RunResult>> fs = new ArrayList<>();
                for (int i = 0; i < n; i++) fs.add(pool.submit((Callable<CodeRunner.RunResult>) () -> runner.run(files)));
                for (var f : fs) results.add(f.get());
            }
            long total = (System.nanoTime() - t0) / 1_000_000;
            long avg = (long) results.stream().mapToLong(CodeRunner.RunResult::millis).average().orElse(0);
            long max = results.stream().mapToLong(CodeRunner.RunResult::millis).max().orElse(0);
            long ok = results.stream().filter(r -> r.passed() == 5 && r.failed() == 0).count();
            long timeouts = results.stream().filter(r -> r.lines().stream().anyMatch(l -> l.startsWith("[TIMEOUT]"))).count();
            table.append(String.format("%-6d %-10d %-10d %-10d %-8d %d / %d%n", n, total, avg, max, ok, n - ok, timeouts));
        }
        System.out.println(table);
        Files.writeString(Path.of("build", "sandbox-bench.txt"), table.toString());
        assertEquals(0, table.toString().lines().skip(2).filter(l -> l.endsWith("/ 0") == false).count(), "시간 초과가 났다\n" + table);
    }

    private static String dockerCpus() throws IOException {
        Process p = new ProcessBuilder("docker", "info", "--format", "{{.NCPU}}").start();
        return new String(p.getInputStream().readAllBytes()).trim();
    }
}
