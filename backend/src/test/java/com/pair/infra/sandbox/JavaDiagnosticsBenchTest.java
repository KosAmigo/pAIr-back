package com.pair.infra.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.pair.domain.task.TestTasks;

/**
 * 편집기 도우미(오류 밑줄·자동 완성·정의로 이동) 분석의 속도와 메모리, 동시 요청 한계를 잰다 (빌드에서는 건너뜀).
 * 실행: PAIR_BENCH=1 [PAIR_BENCH_LEVELS=1,4,8,16,32] sh gradlew test --tests '*JavaDiagnosticsBenchTest'
 * 결과 표는 build/diagnostics-bench.txt 에 남는다.
 */
@EnabledIfEnvironmentVariable(named = "PAIR_BENCH", matches = ".+")
class JavaDiagnosticsBenchTest {

    private static final int REQUESTS_PER_CLIENT = 10;

    private final JavaDiagnostics diagnostics = new JavaDiagnostics();

    private static long usedMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) >> 20;
    }

    private static String stats(List<Long> ms) {
        List<Long> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return String.format("평균 %.0f / 중간 %d / 95%% %d / 최대 %d ms", avg, sorted.get(sorted.size() / 2),
                sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1)), sorted.get(sorted.size() - 1));
    }

    @Test
    void measure() throws Exception {
        Map<String, String> files = new HashMap<>();
        TestTasks.fixtureFiles("order-deadlock", "files").forEach((k, v) -> {
            if (k.startsWith("src/") || k.startsWith("test/")) files.put(k, v);
        });
        String path = "src/shop/OrderService.java";
        String text = files.get(path);
        int dot = text.indexOf("inventory.") + "inventory.".length();
        int name = text.indexOf("Order order = new Order(") + "Order order = new Or".length();
        long chars = files.values().stream().mapToLong(String::length).sum();

        StringBuilder out = new StringBuilder();
        out.append("호스트 코어 ").append(Runtime.getRuntime().availableProcessors()).append(", 파일 ").append(files.size())
                .append("개 ").append(chars).append("자 (order-deadlock), 동시 분석 한도 ").append(JavaDiagnostics.MAX_CONCURRENT).append("\n\n");

        for (int i = 0; i < 10; i++) diagnostics.check(files); // 예열 (JDK 클래스 목록 캐시)
        System.gc();
        long baseMb = usedMb();

        out.append("[한 요청 속도: 혼자 순서대로 50번]\n");
        Map<String, Callable<Object>> ops = new java.util.LinkedHashMap<>();
        ops.put("오류 밑줄(check)", () -> diagnostics.check(files));
        ops.put("자동 완성 - 식.(complete)", () -> diagnostics.complete(files, path, dot));
        ops.put("자동 완성 - 이름(complete)", () -> diagnostics.complete(files, path, name));
        ops.put("정의로 이동(definition)", () -> diagnostics.definition(files, path, text.indexOf("inventory", dot)));
        for (var op : ops.entrySet()) {
            for (int i = 0; i < 5; i++) op.getValue().call(); // 작업마다 예열 (처음 한 번만 채우는 캐시·JIT 가 평균을 흐리지 않게)
            List<Long> ms = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                long t = System.nanoTime();
                op.getValue().call();
                ms.add((System.nanoTime() - t) / 1_000_000);
            }
            out.append(String.format("  %-26s %s%n", op.getKey(), stats(ms)));
        }

        out.append("\n[동시 응답: 클라이언트마다 오류 밑줄 요청을 쉬지 않고 ").append(REQUESTS_PER_CLIENT).append("번]\n");
        out.append(String.format("  %-6s %-10s %-12s %-44s %-8s %-10s%n", "동시", "전체(ms)", "처리(건/초)", "응답 시간", "거절", "힙 최대(MB)"));
        for (String level : System.getenv().getOrDefault("PAIR_BENCH_LEVELS", "1,4,8,16,32").split(",")) {
            int clients = Integer.parseInt(level.trim());
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicLong rejected = new AtomicLong();
            AtomicLong peak = new AtomicLong();
            AtomicBoolean running = new AtomicBoolean(true);
            Thread sampler = Thread.ofPlatform().daemon().start(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(usedMb(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            long t0 = System.nanoTime();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> fs = new ArrayList<>();
                for (int c = 0; c < clients; c++) {
                    fs.add(pool.submit(() -> {
                        for (int i = 0; i < REQUESTS_PER_CLIENT; i++) {
                            long t = System.nanoTime();
                            try {
                                diagnostics.check(files);
                                latencies.add((System.nanoTime() - t) / 1_000_000);
                            } catch (IllegalStateException busy) {
                                rejected.incrementAndGet();
                            }
                        }
                    }));
                }
                for (var f : fs) f.get();
            }
            long total = (System.nanoTime() - t0) / 1_000_000;
            running.set(false);
            sampler.join(500);
            out.append(String.format("  %-6d %-10d %-12.1f %-44s %-8d %-10d%n", clients, total,
                    latencies.size() * 1000.0 / total, latencies.isEmpty() ? "-" : stats(latencies), rejected.get(), peak.get()));
        }
        out.append("\n분석 전 힙 사용량 ").append(baseMb).append("MB (예열 후)\n");
        Files.writeString(Path.of("build/diagnostics-bench.txt"), out.toString());
        System.out.println(out);
    }
}
