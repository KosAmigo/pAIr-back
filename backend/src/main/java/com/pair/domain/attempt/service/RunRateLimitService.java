package com.pair.domain.attempt.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pair.domain.attempt.exception.TooManyRunsException;
import com.pair.global.config.PairProperties;

/**
 * 빌드·실행(컨테이너를 만드는 요청)을 한 사람이 너무 자주 누르지 못하게 한다. 한 사람이 동시 실행 자리를 계속 차지하면
 * 다른 응시자가 줄을 서다 503 을 받기 때문이다. 키 하나당 최근 1분 안의 횟수를 세고, 넘으면 TooManyRunsException(429).
 * 서버 메모리에서 세므로 서버 1대 기준이다(여러 대로 늘리면 공용 저장소가 필요하다).
 */
@Service
public class RunRateLimitService {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    /** 키가 이만큼 쌓이면 1분이 지나 비어 있는 키를 한 번 쓸어 낸다(끝난 응시의 키가 메모리에 남지 않게) */
    private static final int SWEEP_AT = 1000;

    private final Clock clock;
    private final int perMinute;
    private final Map<String, Deque<Instant>> calls = new HashMap<>();

    public RunRateLimitService(PairProperties props, Clock clock) {
        this.clock = clock;
        this.perMinute = props.run().rateLimitPerMinute();
    }

    /** 이번 요청을 센다. 1분에 허용한 횟수를 넘었으면 던진다(넘은 요청은 세지 않는다) */
    public void check(String key) {
        Instant now = clock.instant();
        synchronized (calls) {
            if (calls.size() >= SWEEP_AT) sweep(now);
            Deque<Instant> q = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
            prune(q, now);
            if (q.size() >= perMinute) {
                long wait = Math.max(1, Duration.between(now, q.peekFirst().plus(WINDOW)).toSeconds() + 1);
                throw new TooManyRunsException("빌드·실행을 너무 자주 눌렀습니다. 1분에 " + perMinute + "번까지 할 수 있습니다. "
                        + wait + "초 뒤에 다시 시도해 주세요.");
            }
            q.addLast(now);
        }
    }

    private static void prune(Deque<Instant> q, Instant now) {
        while (!q.isEmpty() && Duration.between(q.peekFirst(), now).compareTo(WINDOW) >= 0) q.pollFirst();
    }

    private void sweep(Instant now) {
        calls.values().forEach(q -> prune(q, now));
        calls.values().removeIf(Deque::isEmpty);
    }
}
