package com.pair.exam.relay;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 한 칸짜리 우편함: 보내는 쪽이 느릴 때 쌓이는 프레임을 가장 최신 한 장만 남기고 버린다 (오래된 장면은 감독에 쓸모가 없다).
 * offer 가 true 를 돌려준 호출이 전송 루프를 시작하고, 루프는 take 가 null 을 줄 때까지 돈다.
 */
final class LatestFrameMailbox {

    private byte[] slot;
    private boolean draining;
    private final AtomicLong dropped = new AtomicLong();

    /** @return true 면 지금 전송 중인 루프가 없으니 호출자가 전송을 시작해야 한다 */
    synchronized boolean offer(byte[] frame) {
        if (slot != null) dropped.incrementAndGet();
        slot = frame;
        if (draining) return false;
        draining = true;
        return true;
    }

    /** 다음에 보낼 프레임. 없으면 null 이고 전송 상태가 끝난다 */
    synchronized byte[] take() {
        byte[] frame = slot;
        slot = null;
        if (frame == null) draining = false;
        return frame;
    }

    long dropped() {
        return dropped.get();
    }
}
