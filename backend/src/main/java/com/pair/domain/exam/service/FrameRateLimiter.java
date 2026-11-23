package com.pair.domain.exam.service;

/**
 * 연결 하나가 1초에 보낼 수 있는 프레임 수 제한 (1초 창). 초과한 프레임은 버리고, 한 창에서 초과가 maxExcessPerWindow 를 넘으면
 * 남용으로 보고 연결을 끊게 한다. 한 연결의 스레드에서만 부른다.
 */
public final class FrameRateLimiter {

    private static final long WINDOW_MS = 1_000;

    private final int maxPerWindow;
    private final int maxExcessPerWindow;
    private long windowStart = Long.MIN_VALUE;
    private int count;
    private int excess;

    public FrameRateLimiter(int maxPerWindow, int maxExcessPerWindow) {
        this.maxPerWindow = maxPerWindow;
        this.maxExcessPerWindow = maxExcessPerWindow;
    }

    public boolean allow(long nowMs) {
        if (windowStart == Long.MIN_VALUE || nowMs - windowStart >= WINDOW_MS) {
            windowStart = nowMs;
            count = 0;
            excess = 0;
        }
        if (count < maxPerWindow) {
            count++;
            return true;
        }
        excess++;
        return false;
    }

    public boolean abusive() {
        return excess > maxExcessPerWindow;
    }
}
