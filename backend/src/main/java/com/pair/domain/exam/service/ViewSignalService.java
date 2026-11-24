package com.pair.domain.exam.service;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 감독관 실시간 영상 시그널링(view-rtc) 형식 검사. 서버는 내용을 해석하지 않고 알려진 필드만 골라 새로 만든 값을 반대편으로 넘긴다.
 * 방향마다 보낼 수 있는 종류가 정해져 있다 (감독관은 offer 를 보내지 못하고 응시자 PC 는 answer 를 보내지 못한다).
 * 프론트 src/proctor/relay/viewerSignal.ts · rtcSignal.ts 와 같은 값을 쓴다.
 */
@Component
public class ViewSignalService {

    public enum Direction { VIEWER_TO_PC, PC_TO_VIEWER }

    /** 감독관이 볼 수 있는 영상 채널 */
    public static final Set<String> CHANNELS = Set.of("phone", "webcam", "screen");
    public static final int MAX_SDP_LENGTH = 32_000;
    public static final int MAX_CANDIDATE_LENGTH = 2_000;
    private static final int MAX_MID_LENGTH = 64;
    private static final int MAX_FRAGMENT_LENGTH = 256;
    private static final int MAX_MLINE_INDEX = 64;

    private static final Set<String> FROM_VIEWER = Set.of("answer", "candidate", "request");
    private static final Set<String> FROM_PC = Set.of("offer", "candidate", "unavailable");

    private final ObjectMapper json;

    public ViewSignalService(ObjectMapper json) {
        this.json = json;
    }

    public boolean isChannel(String channel) {
        return CHANNELS.contains(channel);
    }

    /** 방향에 맞는 신호이고 형식이 맞으면 알려진 필드만 담은 새 객체, 아니면 null */
    public ObjectNode sanitize(JsonNode signal, Direction direction) {
        if (signal == null || !signal.isObject()) return null;
        String kind = signal.path("kind").asText("");
        if (!(direction == Direction.VIEWER_TO_PC ? FROM_VIEWER : FROM_PC).contains(kind)) return null;
        ObjectNode out = json.createObjectNode().put("kind", kind);
        switch (kind) {
            case "offer", "answer" -> {
                JsonNode sdp = signal.get("sdp");
                if (sdp == null || !sdp.isTextual() || sdp.asText().isEmpty() || sdp.asText().length() > MAX_SDP_LENGTH) return null;
                out.put("sdp", sdp.asText());
            }
            case "candidate" -> {
                ObjectNode candidate = candidateOf(signal.get("candidate"));
                if (candidate == null) return null;
                out.set("candidate", candidate);
            }
            default -> { /* request, unavailable 는 값이 없다 */ }
        }
        return out;
    }

    private ObjectNode candidateOf(JsonNode c) {
        if (c == null || !c.isObject()) return null;
        JsonNode text = c.get("candidate");
        if (text == null || !text.isTextual() || text.asText().length() > MAX_CANDIDATE_LENGTH) return null;
        ObjectNode out = json.createObjectNode().put("candidate", text.asText());
        putText(out, "sdpMid", c.get("sdpMid"), MAX_MID_LENGTH);
        putText(out, "usernameFragment", c.get("usernameFragment"), MAX_FRAGMENT_LENGTH);
        JsonNode index = c.get("sdpMLineIndex");
        if (index != null && index.isInt() && index.asInt() >= 0 && index.asInt() <= MAX_MLINE_INDEX) out.put("sdpMLineIndex", index.asInt());
        else out.putNull("sdpMLineIndex");
        return out;
    }

    /** 문자열이 아니거나 너무 길면 null 로 둔다 (후보 자체는 버리지 않는다) */
    private static void putText(ObjectNode out, String key, JsonNode value, int max) {
        if (value != null && value.isTextual() && value.asText().length() <= max) out.put(key, value.asText());
        else out.putNull(key);
    }
}
