package com.pair.domain.exam.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pair.global.config.VisionProperties;
import com.pair.infra.ai.InferenceClient;

/**
 * 추론 서버 상태 감시. /readyz 를 readyzIntervalMs 마다 부르고, 연속 실패(또는 프레임 전달 연속 실패)가 failureThreshold 에 이르면
 * 서버 추론을 "내려감(down)"으로 본다. down 이면 프레임을 보내지 않고 응시자는 감독 중단으로 기록된다. 복구되면 자동으로 올라온다.
 * 응시자별 생존 신호(state)의 마지막 시각도 여기서 들고 있다 (프레임은 오는데 state 가 없는 응시자를 찾으려고).
 */
@Service
public class VisionHealthService {
    private static final Logger log = LoggerFactory.getLogger(VisionHealthService.class);

    private final InferenceClient client;
    private final VisionProperties props;
    private final AtomicInteger readyFailures = new AtomicInteger();
    private final AtomicInteger sendFailures = new AtomicInteger();
    private final Map<String, Long> lastState = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean up = true;

    public VisionHealthService(InferenceClient client, VisionProperties props) {
        this.client = client;
        this.props = props;
    }

    public boolean isUp() {
        return up;
    }

    /** 상태가 바뀔 때(up↔down) 불리는 리스너 */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** 프레임 전달 결과. 연속 실패가 한계에 이르면 down */
    public void recordSend(boolean ok) {
        if (ok) sendFailures.set(0);
        else sendFailures.incrementAndGet();
        evaluate();
    }

    public void recordState(String sessionKey, long nowMs) {
        lastState.put(sessionKey, nowMs);
    }

    /** 이 응시자의 마지막 생존 신호 시각 (없으면 fallback) */
    public long lastState(String sessionKey, long fallbackMs) {
        return lastState.getOrDefault(sessionKey, fallbackMs);
    }

    public void forget(String sessionKey) {
        lastState.remove(sessionKey);
    }

    @Scheduled(fixedDelayString = "${pair.vision.readyz-interval-ms:5000}", initialDelay = 5_000)
    public void probe() {
        if (!props.enabled()) return;
        boolean ok;
        try {
            ok = client.ready();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) {
            readyFailures.set(0);
            sendFailures.set(0); // 준비됨이 확인되면 전달 실패 기록도 비운다
        } else {
            readyFailures.incrementAndGet();
        }
        evaluate();
    }

    private synchronized void evaluate() {
        boolean nowUp = readyFailures.get() < props.failureThreshold() && sendFailures.get() < props.failureThreshold();
        if (nowUp == up) return;
        up = nowUp;
        log.warn("서버 추론 상태 변경: {}", nowUp ? "복구됨" : "중단됨");
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                log.warn("서버 추론 상태 리스너 오류: {}", e.getClass().getSimpleName());
            }
        }
    }
}
