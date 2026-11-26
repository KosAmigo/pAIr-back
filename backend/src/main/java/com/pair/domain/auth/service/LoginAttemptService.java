package com.pair.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.pair.domain.auth.exception.TooManyLoginAttemptsException;
import com.pair.global.config.LoginLimitProperties;
import com.pair.global.error.UnauthorizedException;

/**
 * 로그인 실패 횟수 제한. 비밀번호 무차별 대입(봇)을 막는다. 넘으면 비밀번호가 맞아도 TooManyLoginAttemptsException(429).
 * 가입 여부가 드러나지 않게 없는 계정도 똑같이 센다.
 * 동시에 여러 번 보내 제한을 앞지르지 못하도록 확인하기 전에 먼저 세고, 실패가 아니면 되돌린다.
 * 서버 메모리에서 세므로 서버 1대 기준이고 재시작하면 초기화된다(여러 대로 늘리면 공용 저장소가 필요하다).
 */
@Service
public class LoginAttemptService {

    /** 키가 이만큼 쌓이면 기간이 지난 기록을 쓸어 낸다 */
    private static final int SWEEP_AT = 1000;
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final LoginLimitProperties limits;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();
    private Instant lastSweep = Instant.EPOCH;

    public LoginAttemptService(LoginLimitProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /** login 을 실행하고 실패(UnauthorizedException)를 센다. 성공하면 이 사람(이메일+IP)의 실패 기록을 지운다 */
    public <T> T guard(String email, String ip, Supplier<T> login) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        String both = "both:" + normalized + "|" + ip;
        Map<String, Integer> keys = Map.of(
                both, limits.perEmailAndIp(),
                "email:" + normalized, limits.perEmail(),
                "ip:" + ip, limits.perIp());
        Instant at = reserve(keys);
        T result;
        try {
            result = login.get();
        } catch (UnauthorizedException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            // DB 오류처럼 비밀번호와 상관없는 실패는 세지 않는다
            release(keys, at);
            throw e;
        }
        release(keys, at);
        synchronized (failures) {
            failures.remove(both);
        }
        return result;
    }

    private Instant reserve(Map<String, Integer> keys) {
        synchronized (failures) {
            // 기록이 시각 순서로 쌓이도록 잠금 안에서 시각을 읽는다
            Instant now = clock.instant();
            sweep(now);
            long wait = 0;
            for (var key : keys.entrySet()) {
                Deque<Instant> times = failures.get(key.getKey());
                if (times == null) continue;
                prune(times, now);
                if (times.size() >= key.getValue()) {
                    wait = Math.max(wait, Math.max(1, Duration.between(now, times.peekFirst().plus(limits.window())).toSeconds() + 1));
                }
            }
            // 막힌 요청은 세지 않는다. 계속 눌러도 대기 시간이 늘어나지 않는다
            if (wait > 0) throw new TooManyLoginAttemptsException(wait);
            keys.keySet().forEach(k -> failures.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(now));
            return now;
        }
    }

    private void release(Map<String, Integer> keys, Instant at) {
        synchronized (failures) {
            for (String key : keys.keySet()) {
                Deque<Instant> times = failures.get(key);
                if (times == null) continue;
                times.removeLastOccurrence(at);
                if (times.isEmpty()) failures.remove(key);
            }
        }
    }

    private void prune(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && Duration.between(times.peekFirst(), now).compareTo(limits.window()) >= 0) times.pollFirst();
    }

    private void sweep(Instant now) {
        if (failures.size() < SWEEP_AT || Duration.between(lastSweep, now).compareTo(SWEEP_INTERVAL) < 0) return;
        lastSweep = now;
        failures.values().forEach(times -> prune(times, now));
        failures.values().removeIf(Deque::isEmpty);
    }
}
