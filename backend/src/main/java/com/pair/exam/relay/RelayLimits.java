package com.pair.exam.relay;

/** 휴대폰 프레임 중계 한도. 장당 13~15KB 를 목표로 하므로 100KB 를 넘으면 잘못된 값으로 본다 */
public final class RelayLimits {
    public static final int MAX_FRAME_BYTES = 100_000;
    public static final int MAX_TEXT_BYTES = 4_096;
    /** 휴대폰이 이 시간 동안 아무 신호가 없으면 응답 없음(stale)으로 본다. 생존 신호는 5초마다 온다 */
    public static final long PHONE_STALE_MS = 15_000;
    /** PC 와 휴대폰 모두 없는 짝은 이 시간 뒤에 지운다 (PC 새로고침 동안은 같은 짝 코드를 유지) */
    public static final long EMPTY_PAIR_TTL_MS = 10 * 60_000;

    private RelayLimits() {}
}
