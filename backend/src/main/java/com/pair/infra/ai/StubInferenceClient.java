package com.pair.infra.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 가짜 추론 서버: 추론 서버 저장소가 생기기 전의 자리 채움이다. 프레임을 세기만 하고 내용은 보관하지 않으며 로그에도 남기지 않는다.
 * 항상 준비됨으로 답하고, 시험 모드로 등록된 세션에는 2초마다 생존 신호(state)를 돌려주며, 점검 모드 세션에는 모두 통과한 점검 판정을 돌려준다.
 * 실제 구현으로 바꿀 때는 이 클래스를 지우고 InferenceClient 구현을 Bean 으로 둔다.
 */
@Component
public class StubInferenceClient implements InferenceClient {

    private final ObjectMapper json;
    private final Map<String, Mode> sessions = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private volatile Listener listener;

    public StubInferenceClient(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public StartResult start(String sessionKey, Mode mode, long startedAtMs, boolean recalibrate) {
        sessions.put(sessionKey, mode);
        return StartResult.ACCEPTED;
    }

    @Override
    public boolean changeMode(String sessionKey, long startedAtMs) {
        return sessions.replace(sessionKey, Mode.EXAM) != null;
    }

    @Override
    public void end(String sessionKey) {
        sessions.remove(sessionKey);
    }

    @Override
    public void sendFrame(String sessionKey, Channel channel, long seq, long recvMs, byte[] jpeg) {
        received.incrementAndGet();
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 시험 모드 세션에 생존 신호를, 점검 모드 세션에 통과 판정을 보낸다 */
    @Scheduled(fixedDelay = 2_000)
    void pushStates() {
        Listener l = listener;
        if (l == null) return;
        JsonNode passed = null;
        for (Map.Entry<String, Mode> e : sessions.entrySet()) {
            if (e.getValue() == Mode.EXAM) {
                l.onState(e.getKey());
            } else {
                if (passed == null) passed = checkBody();
                l.onCheck(e.getKey(), passed);
            }
        }
    }

    private JsonNode checkBody() {
        ObjectNode items = json.createObjectNode();
        for (String k : new String[] {"keyboard", "mouse", "leftHand", "rightHand", "desk"}) items.put(k, true);
        ObjectNode body = json.createObjectNode();
        body.set("items", items);
        body.set("hints", json.createArrayNode());
        body.put("holdMs", 3000);
        body.put("passed", true);
        return body;
    }

    public long received() {
        return received.get();
    }
}
