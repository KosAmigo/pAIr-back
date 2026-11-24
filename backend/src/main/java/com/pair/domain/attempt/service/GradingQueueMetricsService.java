package com.pair.domain.attempt.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 모니터링용 "채점 대기 건수". 채점 큐가 따로 없어서, 제출을 마치고 채점 단계(SCORING)에 있는 응시 수를 센다.
 * Prometheus 가 15초마다 읽을 때마다 DB 를 치지 않도록 결과를 잠깐(10초) 들고 있는다.
 */
@Service
public class GradingQueueMetricsService implements MeterBinder {

    static final Duration CACHE = Duration.ofSeconds(10);

    private final AttemptRepository attempts;
    private final AtomicLong cached = new AtomicLong();
    private volatile long readAtNanos = System.nanoTime() - CACHE.toNanos() - 1;

    public GradingQueueMetricsService(AttemptRepository attempts) {
        this.attempts = attempts;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("pair_grading_pending", this, GradingQueueMetricsService::pending)
                .description("채점 단계(SCORING)에서 기다리거나 채점 중인 응시 수")
                .register(registry);
    }

    /** 캐시가 지났으면 DB 에서 다시 읽는다. DB 가 안 되면 이전 값을 그대로 돌려줘 지표 수집이 실패하지 않게 한다. */
    double pending() {
        long now = System.nanoTime();
        if (now - readAtNanos >= CACHE.toNanos()) {
            try {
                cached.set(attempts.countByStatus(AttemptStateDto.Status.SCORING));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(GradingQueueMetricsService.class)
                        .warn("채점 대기 건수를 읽지 못했습니다: {}", e.getMessage());
            }
            readAtNanos = now;
        }
        return cached.get();
    }
}
