package com.pair.domain.exam.service;

/** 휴대폰 프레임 중계 한도. 장당 13~15KB 를 목표로 하므로 100KB 를 넘으면 잘못된 값으로 본다 */
public final class RelayLimitService {
    public static final int MAX_FRAME_BYTES = 100_000;
    public static final int MAX_TEXT_BYTES = 4_096;
    /** 영상 시그널링(view-rtc)을 주고받는 PC·감독관 연결의 텍스트 메시지 상한. sdp 32,000자 + 여유 */
    public static final int MAX_SIGNAL_TEXT_BYTES = 40_000;
    /** 응시자 1명을 동시에 볼 수 있는 감독관 수 (프론트 watchRoom.ts 의 MAX_VIEWERS_PER_PARTICIPANT 와 같은 값). 응시자 PC 가 감독관마다 영상을 따로 인코딩한다 */
    public static final int MAX_VIEWERS_PER_PARTICIPANT = 2;
    /** PC·감독관 연결 하나가 1초에 보낼 수 있는 시그널링 수 (넘는 것은 버리고, 심하면 끊는다) */
    public static final int MAX_SIGNALS_PER_SEC = 60;
    public static final int MAX_SIGNAL_EXCESS_PER_SEC = 300;
    /** 감독관 연결이 watch 를 다시 보낼 수 있는 최소 간격 (권한 확인이 DB 를 읽으므로 연타를 막는다) */
    public static final long MIN_WATCH_INTERVAL_MS = 500;
    /** 휴대폰이 이 시간 동안 아무 신호가 없으면 응답 없음(stale)으로 본다. 생존 신호는 5초마다 온다 */
    public static final long PHONE_STALE_MS = 15_000;
    /** PC 와 휴대폰 모두 없는 짝은 이 시간 뒤에 지운다 (PC 새로고침 동안은 같은 짝 코드를 유지) */
    public static final long EMPTY_PAIR_TTL_MS = 10 * 60_000;

    /** 서버 전체 상한 (초과하면 새 연결·짝을 거절). 값은 임시이며 구현 뒤 실측으로 정한다 */
    public static final int MAX_CONNECTIONS = 1_000;
    public static final int MAX_PHONES = 300;
    public static final int MAX_PAIRS = 1_000;
    public static final int MAX_PRACTICE_PAIRS = 100;
    /** 휴대폰 한 대가 1초에 보낼 수 있는 프레임 수 (넘는 프레임은 버리고, 초과가 심하면 연결을 끊는다) */
    public static final int MAX_FRAMES_PER_SEC = 5;
    public static final int MAX_EXCESS_PER_SEC = 50;
    /** 연결한 뒤 이 시간 안에 hello 를 보내지 않으면 닫는다 */
    public static final long HELLO_TIMEOUT_MS = 10_000;
    /** 사전 테스트(연습) 짝은 이 시간 비어 있으면 지운다 */
    public static final long EMPTY_PRACTICE_TTL_MS = 60_000;

    private RelayLimitService() {}
}
