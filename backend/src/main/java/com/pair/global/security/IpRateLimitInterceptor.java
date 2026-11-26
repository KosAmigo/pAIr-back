package com.pair.global.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.pair.global.config.IpLimitProperties;
import com.pair.global.error.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인하지 않고 부를 수 있는 API(인증 메일, 가입, 기업 문의)를 한 IP 에서 너무 많이 부르지 못하게 한다(429).
 * 이메일을 바꿔 가며 메일을 보내거나 계정·문의를 대량으로 만드는 봇을 막는다. 내용이 틀린 요청도 센다.
 * 서버 메모리에서 세므로 서버 1대 기준이고 재시작하면 초기화된다(여러 대로 늘리면 공용 저장소가 필요하다).
 */
@Component
public class IpRateLimitInterceptor implements HandlerInterceptor {

    /** 키가 이만큼 쌓이면 기간이 지난 기록을 쓸어 낸다 */
    private static final int SWEEP_AT = 1000;
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private record Rule(String name, int limit) {}

    private final ClientIpResolver clientIp;
    private final Clock clock;
    private final Duration window;
    /** 컨트롤러 경로 → 제한. 같은 이름은 횟수를 합쳐서 센다 */
    private final Map<String, Rule> rules;
    private final Map<String, Deque<Instant>> calls = new HashMap<>();
    private Instant lastSweep = Instant.EPOCH;

    public IpRateLimitInterceptor(IpLimitProperties limits, ClientIpResolver clientIp, Clock clock) {
        this.clientIp = clientIp;
        this.clock = clock;
        this.window = limits.window();
        Rule mail = new Rule("mail", limits.mail());
        Rule signup = new Rule("signup", limits.signup());
        this.rules = Map.of(
                "/api/auth/email-verifications", mail,
                "/api/company-invites/{token}/email-verifications", mail,
                "/api/auth/signup", signup,
                "/api/company-invites/accept", signup,
                "/api/company-inquiries", new Rule("inquiry", limits.inquiry()));
    }

    /** 이 검사를 걸 경로 (AuthWebConfig) */
    public String[] paths() {
        return rules.keySet().toArray(String[]::new);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Rule rule = pattern == null ? null : rules.get(pattern.toString());
        if (rule != null) check(rule, clientIp.resolve(request));
        return true;
    }

    private void check(Rule rule, String ip) {
        synchronized (calls) {
            Instant now = clock.instant();
            sweep(now);
            Deque<Instant> times = calls.computeIfAbsent(rule.name() + "|" + ip, k -> new ArrayDeque<>());
            prune(times, now);
            // 막힌 요청은 세지 않는다. 계속 보내도 대기 시간이 늘어나지 않는다
            if (times.size() >= rule.limit()) {
                throw new TooManyRequestsException(Math.max(1, Duration.between(now, times.peekFirst().plus(window)).toSeconds() + 1));
            }
            times.addLast(now);
        }
    }

    private void prune(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && Duration.between(times.peekFirst(), now).compareTo(window) >= 0) times.pollFirst();
    }

    private void sweep(Instant now) {
        if (calls.size() < SWEEP_AT || Duration.between(lastSweep, now).compareTo(SWEEP_INTERVAL) < 0) return;
        lastSweep = now;
        calls.values().forEach(times -> prune(times, now));
        calls.values().removeIf(Deque::isEmpty);
    }
}
