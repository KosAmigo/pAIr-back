package com.pair.domain.scoring.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.pair.domain.scoring.dto.ScoringJobDto;
import com.pair.global.config.ScoringQueueProperties;

/**
 * 채점 큐에서 작업을 꺼내 채점하는 작업자. pair.scoring-queue.enabled=true 일 때만 켜진다.
 * 실패하면 maxAttempts 까지 시도 횟수만큼 늘려 가며 다시 시도하고, 그래도 실패하면 응시를 ERROR 로 두고 실패 보관함에 남긴다.
 * 같은 작업이 두 번 전달돼도(작업자 죽음 뒤 되살림 등) 이미 채점이 끝난 응시는 다시 채점하지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "pair.scoring-queue", name = "enabled", havingValue = "true")
public class ScoringWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ScoringWorkerService.class);
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Duration REDIS_DOWN_PAUSE = Duration.ofSeconds(5);

    private final ScoringQueueService queue;
    private final ScoringService scoring;
    private final ScoringQueueProperties props;
    private final List<Thread> workers = new ArrayList<>();
    private ScheduledExecutorService maintenance;
    private final Duration shutdownWait;
    private volatile boolean running;
    /** 마지막으로 Redis 에서 작업을 꺼내 본 결과. 1 = 됨, 0 = 연결 안 됨 (지표 pair_scoring_queue_up) */
    private final AtomicInteger queueUp = new AtomicInteger(1);

    public ScoringWorkerService(ScoringQueueService queue, ScoringService scoring, ScoringQueueProperties props,
                                @Value("${pair.scoring.shutdown-wait:90s}") Duration shutdownWait) {
        this.shutdownWait = shutdownWait;
        this.queue = queue;
        this.scoring = scoring;
        this.props = props;
    }

    @PostConstruct
    void start() {
        // 전체 /actuator/health 는 Redis 가 죽으면 DOWN 이지만, 배포 확인은 Redis 를 뺀 core 그룹을 본다.
        // 그래서 Redis 장애는 이 지표와 경고(monitoring/alerts.yml ScoringQueueDown)로 따로 알린다
        Gauge.builder("pair_scoring_queue_up", queueUp, AtomicInteger::get)
                .description("채점 큐(Redis)에서 작업을 꺼낼 수 있는지 (1 = 됨, 0 = 안 됨)")
                .register(Metrics.globalRegistry);
        // 작업자 이름은 프로세스마다 새로 정해지므로, 지금 처리 중 목록에 있는 작업은 모두 이전 프로세스(재시작·강제 종료)가 남긴 것이다.
        // staleAfter 를 기다리지 않고 바로 되살린다. 서버가 하나라는 전제(두 서버가 겹쳐 돌면 다시 검토)
        guarded(() -> {
            int n = queue.recoverStale(Duration.ZERO, props.maxAttempts());
            if (n > 0) log.info("이전 서버가 끝내지 못한 채점 작업 {}건을 되살렸습니다", n);
        });
        running = true;
        for (int i = 1; i <= props.workers(); i++) {
            Thread t = new Thread(this::loop, "scoring-worker-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scoring-queue-maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleWithFixedDelay(() -> guarded(queue::promoteDue), 5, 5, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(() -> guarded(() -> queue.recoverStale(props.staleAfter(), props.maxAttempts())),
                30, 60, TimeUnit.SECONDS);
        log.info("채점 큐 작업자 {}개를 시작했습니다", props.workers());
    }

    /**
     * 안전한 종료: 새 작업은 꺼내지 않고, 하던 채점은 shutdownWait 까지 끝내게 둔다. 그래도 남은 작업자만 중단시킨다.
     * 중단된 작업은 Redis 의 처리 중 목록에 남아 recoverStale 이 되살린다.
     */
    @PreDestroy
    void stop() {
        running = false;
        if (maintenance != null) maintenance.shutdownNow();
        long deadline = System.nanoTime() + shutdownWait.toNanos();
        for (Thread t : workers) {
            long left = deadline - System.nanoTime();
            try {
                if (left > 0) t.join(Duration.ofNanos(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread t : workers) {
            if (t.isAlive()) {
                log.warn("채점 작업자가 {}초 안에 끝나지 않아 중단합니다: {}", shutdownWait.toSeconds(), t.getName());
                t.interrupt();
            }
        }
    }

    private void loop() {
        while (running) {
            if (!pollOnce()) pause(REDIS_DOWN_PAUSE);
        }
    }

    /**
     * 작업 하나를 꺼내 처리한다. Redis 에 연결할 수 없으면 false (쉬었다가 다시 시도한다. 제출은 그대로 받는다).
     * 결과를 queueUp 에 남겨 지표로 보인다.
     */
    boolean pollOnce() {
        try {
            queue.take(POLL).ifPresent(this::process);
            queueUp.set(1);
            return true;
        } catch (RuntimeException e) {
            if (queueUp.getAndSet(0) == 1) log.warn("채점 큐(Redis)에 연결할 수 없습니다. 연결될 때까지 서버 안에서 바로 채점합니다");
            log.warn("채점 큐에서 작업을 꺼내지 못했습니다 ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 지표 값(테스트용) */
    int queueUp() {
        return queueUp.get();
    }

    void process(ScoringJobDto job) {
        try {
            if (!scoring.isScored(job.attemptId())) scoring.score(job.attemptId());
            queue.complete(job);
        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    private void handleFailure(ScoringJobDto job, Exception e) {
        if (job.attempt() < props.maxAttempts()) {
            Duration delay = props.retryDelay().multipliedBy(job.attempt());
            log.warn("채점 실패, {}초 뒤 다시 시도합니다: attemptId={} ({}/{})", delay.toSeconds(), job.attemptId(),
                    job.attempt(), props.maxAttempts(), e);
            queue.retryLater(job, delay);
            return;
        }
        log.error("채점을 포기합니다: attemptId={} ({}번 시도)", job.attemptId(), job.attempt(), e);
        try {
            scoring.markFailed(job.attemptId(), e);
        } catch (RuntimeException markError) {
            // 응시 기록이 없는 등으로 표시하지 못해도 실패 보관은 해야 작업이 큐에 계속 남지 않는다
            log.warn("응시를 ERROR 로 표시하지 못했습니다: attemptId={} ({})", job.attemptId(), markError.getClass().getSimpleName());
        }
        queue.fail(job, e.getMessage() == null ? e.toString() : e.getMessage());
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("채점 큐 정리 작업을 하지 못했습니다 ({})", e.getClass().getSimpleName());
        }
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
