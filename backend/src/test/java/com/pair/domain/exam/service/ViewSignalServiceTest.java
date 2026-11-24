package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.exam.service.ViewSignalService.Direction;

/** 감독관 영상 시그널링 형식 검사: 방향마다 허용하는 종류, 크기 제한, 알려진 필드만 통과 */
class ViewSignalServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ViewSignalService service = new ViewSignalService(json);

    private JsonNode node(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    @DisplayName("채널은 phone·webcam·screen 만 허용한다")
    void channels() {
        assertThat(service.isChannel("webcam")).isTrue();
        assertThat(service.isChannel("screen")).isTrue();
        assertThat(service.isChannel("phone")).isTrue();
        assertThat(service.isChannel("audio")).isFalse();
        assertThat(service.isChannel("")).isFalse();
    }

    @Test
    @DisplayName("감독관은 answer·candidate·request 만, 응시자 PC 는 offer·candidate·unavailable 만 보낼 수 있다")
    void directions() throws Exception {
        assertThat(service.sanitize(node("{\"kind\":\"answer\",\"sdp\":\"v=0\"}"), Direction.VIEWER_TO_PC)).isNotNull();
        assertThat(service.sanitize(node("{\"kind\":\"request\"}"), Direction.VIEWER_TO_PC)).isNotNull();
        assertThat(service.sanitize(node("{\"kind\":\"offer\",\"sdp\":\"v=0\"}"), Direction.VIEWER_TO_PC)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"unavailable\"}"), Direction.VIEWER_TO_PC)).isNull();

        assertThat(service.sanitize(node("{\"kind\":\"offer\",\"sdp\":\"v=0\"}"), Direction.PC_TO_VIEWER)).isNotNull();
        assertThat(service.sanitize(node("{\"kind\":\"unavailable\"}"), Direction.PC_TO_VIEWER)).isNotNull();
        assertThat(service.sanitize(node("{\"kind\":\"answer\",\"sdp\":\"v=0\"}"), Direction.PC_TO_VIEWER)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"request\"}"), Direction.PC_TO_VIEWER)).isNull();
    }

    @Test
    @DisplayName("휴대폰 제어(torch·quality)와 모르는 종류, 객체가 아닌 값은 거절한다")
    void rejectsUnknown() throws Exception {
        assertThat(service.sanitize(node("{\"kind\":\"torch\",\"on\":true}"), Direction.VIEWER_TO_PC)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"quality\",\"mode\":\"scan\"}"), Direction.VIEWER_TO_PC)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"anything\"}"), Direction.PC_TO_VIEWER)).isNull();
        assertThat(service.sanitize(node("\"offer\""), Direction.PC_TO_VIEWER)).isNull();
        assertThat(service.sanitize(null, Direction.PC_TO_VIEWER)).isNull();
    }

    @Test
    @DisplayName("sdp 는 비어 있거나 32,000자를 넘으면 거절한다")
    void sdpLimits() throws Exception {
        assertThat(service.sanitize(node("{\"kind\":\"offer\",\"sdp\":\"\"}"), Direction.PC_TO_VIEWER)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"offer\"}"), Direction.PC_TO_VIEWER)).isNull();
        ObjectNode ok = json.createObjectNode().put("kind", "offer").put("sdp", "a".repeat(ViewSignalService.MAX_SDP_LENGTH));
        ObjectNode tooLong = json.createObjectNode().put("kind", "offer").put("sdp", "a".repeat(ViewSignalService.MAX_SDP_LENGTH + 1));
        assertThat(service.sanitize(ok, Direction.PC_TO_VIEWER)).isNotNull();
        assertThat(service.sanitize(tooLong, Direction.PC_TO_VIEWER)).isNull();
    }

    @Test
    @DisplayName("ICE 후보는 알려진 필드만 새로 만들어 넘기고, 후보 문자열이 2,000자를 넘으면 거절한다")
    void candidates() throws Exception {
        ObjectNode out = service.sanitize(node("""
                {"kind":"candidate","extra":"x","candidate":{"candidate":"candidate:1 1 udp 1 1.2.3.4 5 typ host","sdpMid":"0",
                 "sdpMLineIndex":0,"usernameFragment":"abcd","evil":"y"}}"""), Direction.PC_TO_VIEWER);
        assertThat(out).isNotNull();
        assertThat(out.has("extra")).isFalse();
        JsonNode c = out.get("candidate");
        assertThat(c.has("evil")).isFalse();
        assertThat(c.get("sdpMid").asText()).isEqualTo("0");
        assertThat(c.get("sdpMLineIndex").asInt()).isZero();
        assertThat(c.get("usernameFragment").asText()).isEqualTo("abcd");

        ObjectNode tooLong = json.createObjectNode().put("kind", "candidate");
        tooLong.putObject("candidate").put("candidate", "c".repeat(ViewSignalService.MAX_CANDIDATE_LENGTH + 1));
        assertThat(service.sanitize(tooLong, Direction.VIEWER_TO_PC)).isNull();
        assertThat(service.sanitize(node("{\"kind\":\"candidate\"}"), Direction.VIEWER_TO_PC)).isNull();
    }

    @Test
    @DisplayName("ICE 후보의 보조 필드가 잘못되면 후보는 두고 그 필드만 null 로 비운다")
    void badOptionalFieldsBecomeNull() throws Exception {
        ObjectNode out = service.sanitize(node("""
                {"kind":"candidate","candidate":{"candidate":"candidate:x","sdpMid":7,"sdpMLineIndex":-3,"usernameFragment":true}}"""),
                Direction.VIEWER_TO_PC);
        assertThat(out).isNotNull();
        assertThat(out.get("candidate").get("sdpMid").isNull()).isTrue();
        assertThat(out.get("candidate").get("sdpMLineIndex").isNull()).isTrue();
        assertThat(out.get("candidate").get("usernameFragment").isNull()).isTrue();
    }
}
