package com.pair.domain.taskgen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pair.infra.sandbox.CodeRunner;

class TaskGenRunServiceTest {

    /** 동시에 몇 개가 도는지 세는 가짜 실행기 */
    static class Counting implements CodeRunner {
        final AtomicInteger now = new AtomicInteger(), max = new AtomicInteger();

        @Override
        public RunResult run(Map<String, String> sources) {
            int n = now.incrementAndGet();
            max.accumulateAndGet(n, Math::max);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now.decrementAndGet();
            return new RunResult(true, List.of(), 1, 0, List.of(), List.of(), 80);
        }

        @Override
        public BuildResult build(Map<String, String> sources) {
            return new BuildResult(true, List.of(), List.of(), 1);
        }
    }

    @Test
    void limitsConcurrentRuns() throws Exception {
        Counting fake = new Counting();
        TaskGenRunService svc = new TaskGenRunService(fake, 2);
        CountDownLatch done = new CountDownLatch(6);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) pool.submit(() -> {
                svc.runner().run(Map.of());
                done.countDown();
            });
            done.await();
        }
        assertEquals(2, fake.max.get());
        assertEquals(0, svc.waiting());
    }

    @Test
    void passesResultThrough() {
        var r = new TaskGenRunService(new Counting(), 1).runner().run(Map.of());
        assertTrue(r.allPassed());
    }
}
