package com.pair.domain.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.scoring.dto.ScoringJobDto;
import com.pair.global.config.ScoringQueueProperties;

/** 서버를 끌 때 하던 채점은 끝내고, 시작하지 않은 채점은 버리는지 (안전한 종료). */
class ScoringShutdownTest {

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Test
    @DisplayName("하던 채점은 끝까지 기다리고, 시작하지 않은 채점은 버린다")
    void finishesRunningAndDropsQueued() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger queuedRan = new AtomicInteger();
        pool.submit(() -> {
            started.countDown();
            sleep(300);
            finished.set(true);
        });
        pool.submit(queuedRan::incrementAndGet);
        pool.submit(queuedRan::incrementAndGet);
        started.await();

        int dropped = ScoringService.drain(pool, Duration.ofSeconds(5));

        assertThat(dropped).isEqualTo(2);
        assertThat(finished).isTrue();
        assertThat(queuedRan).hasValue(0);
        assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("기다리는 시간을 넘기면 하던 채점을 중단시킨다")
    void interruptsAfterWait() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        started.await();

        ScoringService.drain(pool, Duration.ofMillis(100));

        assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("큐 작업자는 종료 때 하던 채점을 끝내고 작업을 완료 처리한다")
    void workerFinishesJobOnStop() {
        ScoringQueueService queue = mock(ScoringQueueService.class);
        ScoringService scoring = mock(ScoringService.class);
        ScoringJobDto job = new ScoringJobDto("1-0", "42", 1);
        AtomicBoolean handedOut = new AtomicBoolean();
        when(queue.take(any())).thenAnswer(inv -> {
            if (handedOut.compareAndSet(false, true)) return Optional.of(job);
            sleep(20);
            return Optional.empty();
        });
        CountDownLatch scoringStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            scoringStarted.countDown();
            sleep(300); // 중단되면 InterruptedException 이 아니라 플래그만 남으므로 아래 complete 로 확인한다
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("interrupted");
            return null;
        }).when(scoring).score("42");
        ScoringQueueProperties props = new ScoringQueueProperties(true, 1, 3, Duration.ofSeconds(30), Duration.ofMinutes(15), null);
        ScoringWorkerService worker = new ScoringWorkerService(queue, scoring, props, Duration.ofSeconds(5));

        worker.start();
        try {
            assertThat(scoringStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        worker.stop();

        verify(queue, timeout(1000)).complete(job);
        verify(queue, never()).retryLater(any(), any());
        // 시작할 때 이전 프로세스가 남긴 작업을 기다리지 않고 되살린다
        verify(queue).recoverStale(Duration.ZERO, 3);
    }

    private static void sleep(long ms) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (System.nanoTime() < end) {
            try {
                Thread.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
