package com.pair.domain.attempt.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pair.domain.attempt.exception.TooManyChatsException;

/**
 * AI 채팅 분당 호출 제한(FR-010·NFR-009). 한 사람(자가진단은 회원 1명, 실전은 응시 1건)은 1분에 perUser 번,
 * 서비스 전체는 1분에 total 번까지. 스트림을 열기 전에 확인해 넘으면 TooManyChatsException(429)을 던진다.
 * 두 한도를 함께 보고 둘 다 통과한 요청만 센다(막힌 요청은 세지 않는다).
 * 서버 메모리에서 세므로 서버 1대 기준이다(빌드·실행 제한 RunRateLimitService 와 같다).
 */
@Service
public class ChatRateLimitService {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    /** 키가 이만큼 쌓이면 1분이 지나 비어 있는 키를 한 번 쓸어 낸다 */
    private static final int SWEEP_AT = 1000;

    private final Clock clock;
    private final int perUser;
    private final int total;
    private final Map<String, Deque<Instant>> calls = new HashMap<>();
    private final Deque<Instant> all = new ArrayDeque<>();

    public ChatRateLimitService(Clock clock,
            @Value("${pair.assistant.rate-limit-per-minute:10}") int perUser,
            @Value("${pair.assistant.total-rate-limit-per-minute:300}") int total) {
        if (perUser <= 0 || total <= 0) throw new IllegalArgumentException("AI 채팅 분당 제한은 양수여야 합니다.");
        this.clock = clock;
        this.perUser = perUser;
        this.total = total;
    }

    /** 이번 질문을 센다. 1분 한도를 넘었으면 던진다 */
    public void check(String key) {
        Instant now = clock.instant();
        synchronized (calls) {
            if (calls.size() >= SWEEP_AT) sweep(now);
            Deque<Instant> mine = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
            prune(mine, now);
            prune(all, now);
            if (mine.size() >= perUser) {
                long wait = waitSeconds(mine, now);
                throw new TooManyChatsException("AI 질문을 너무 자주 보냈습니다. 1분에 " + perUser + "번까지 보낼 수 있습니다. "
                        + wait + "초 뒤에 다시 보내 주세요.", wait);
            }
            if (all.size() >= total) {
                long wait = waitSeconds(all, now);
                throw new TooManyChatsException("지금 AI 질문이 몰려 잠시 받을 수 없습니다. " + wait + "초 뒤에 다시 보내 주세요.", wait);
            }
            mine.addLast(now);
            all.addLast(now);
        }
    }

    private static long waitSeconds(Deque<Instant> q, Instant now) {
        return Math.max(1, Duration.between(now, q.peekFirst().plus(WINDOW)).toSeconds() + 1);
    }

    private static void prune(Deque<Instant> q, Instant now) {
        while (!q.isEmpty() && Duration.between(q.peekFirst(), now).compareTo(WINDOW) >= 0) q.pollFirst();
    }

    private void sweep(Instant now) {
        calls.values().forEach(q -> prune(q, now));
        calls.values().removeIf(Deque::isEmpty);
    }
}
