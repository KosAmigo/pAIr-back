package com.pair.domain.exam;

import com.pair.domain.exam.service.RelayAuthorizer;
import com.pair.domain.exam.service.RelayLimits;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.exam.service.AttemptSubmitter;

/** 휴대폰 JPEG 프레임 중계 (실제 WebSocket). 서버는 프레임을 분석하지 않고 PC 로 그대로 넘긴다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("examtest")
// 다른 통합 테스트 컨텍스트와 H2 메모리 DB(exam)를 나눠 쓰면 create-drop 이 서로의 테이블을 지우므로 전용 DB 를 쓴다
@org.springframework.test.context.TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:relay;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ProctorRelayIntegrationTest {

    /** 짝은 입장 토큰마다 서버 메모리에 남으므로 테스트마다 다른 토큰을 쓴다 */
    static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
    String TOKEN;

    @org.junit.jupiter.api.BeforeEach
    void newToken() {
        TOKEN = String.format("%043d", SEQ.incrementAndGet()).replace('0', 'A');
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean RelayAuthorizer authorizer;
    @MockitoBean AttemptSubmitter attempts;
    @MockitoBean org.springframework.mail.javamail.JavaMailSender mail;

    /** 받은 메시지를 큐에 모으는 테스트 클라이언트 */
    static class Client extends AbstractWebSocketHandler {
        final BlockingQueue<Object> got = new LinkedBlockingQueue<>();
        WebSocketSession session;

        @Override public void afterConnectionEstablished(WebSocketSession s) { session = s; }
        @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { got.add(m.getPayload()); }
        @Override protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) {
            byte[] b = new byte[m.getPayload().remaining()];
            m.getPayload().get(b);
            got.add(b);
        }
        @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus st) { got.add("CLOSED"); }
    }

    private Client connect() throws Exception {
        Client c = new Client();
        new StandardWebSocketClient().execute(c, new org.springframework.web.socket.WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/proctor")).get(5, TimeUnit.SECONDS);
        return c;
    }

    private JsonNode nextJson(Client c) throws Exception {
        Object o = c.got.poll(5, TimeUnit.SECONDS);
        assertThat(o).isInstanceOf(String.class);
        return json.readTree((String) o);
    }

    @Test
    @DisplayName("PC 가 붙으면 짝 코드를 받고, 휴대폰이 그 코드로 붙으면 서로 연결 상태를 받는다")
    void pairing() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        JsonNode paired = nextJson(pc);
        assertThat(paired.get("type").asText()).isEqualTo("paired");
        String code = paired.get("pairCode").asText();
        assertThat(paired.get("phoneUrl").asText()).contains("/proctor/phone?p=" + code);
        assertThat(nextJson(pc).get("phone").asText()).isEqualTo("disconnected");

        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + code + "\"}"));
        assertThat(nextJson(phone).get("type").asText()).isEqualTo("phone-joined");
        JsonNode peer = nextJson(pc);
        assertThat(peer.get("type").asText()).isEqualTo("peer");
        assertThat(peer.get("phone").asText()).isEqualTo("connected");
    }

    @Test
    @DisplayName("휴대폰이 보낸 JPEG 프레임은 PC 로 그대로 넘어가고, 생존 신호는 넘기지 않는다")
    void forwardsFrames() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        String code = nextJson(pc).get("pairCode").asText();
        nextJson(pc); // peer disconnected
        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + code + "\"}"));
        nextJson(phone);
        nextJson(pc); // peer connected

        phone.session.sendMessage(new TextMessage("{\"type\":\"heartbeat\"}"));
        byte[] frame = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        phone.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(frame)));

        Object received = pc.got.poll(5, TimeUnit.SECONDS);
        assertThat(received).isInstanceOf(byte[].class);
        assertThat((byte[]) received).containsExactly(frame);
    }

    @Test
    @DisplayName("JPEG 가 아니거나 너무 큰 프레임은 PC 로 넘기지 않는다")
    void rejectsBadFrames() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        String code = nextJson(pc).get("pairCode").asText();
        nextJson(pc);
        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + code + "\"}"));
        nextJson(phone);
        nextJson(pc);

        phone.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        phone.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[RelayLimits.MAX_FRAME_BYTES + 1])));
        byte[] good = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9};
        phone.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(good)));

        assertThat((byte[]) pc.got.poll(5, TimeUnit.SECONDS)).containsExactly(good); // 앞의 둘은 건너뛰고 이것만 온다
    }

    @Test
    @DisplayName("입장 링크가 틀리면 오류를 알리고 연결을 닫는다")
    void rejectsBadInvite() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(false);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        assertThat(nextJson(pc).get("type").asText()).isEqualTo("error");
        assertThat(pc.got.poll(5, TimeUnit.SECONDS)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("모르는 짝 코드로 붙은 휴대폰은 거절한다")
    void rejectsUnknownPairCode() throws Exception {
        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + "B".repeat(24) + "\"}"));
        assertThat(nextJson(phone).get("type").asText()).isEqualTo("error");
    }

    @Test
    @DisplayName("휴대폰 연결이 끊기면 PC 가 disconnected 를 받는다")
    void phoneDisconnect() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        String code = nextJson(pc).get("pairCode").asText();
        nextJson(pc);
        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + code + "\"}"));
        nextJson(phone);
        nextJson(pc);

        phone.session.close();
        assertThat(nextJson(pc).get("phone").asText()).isEqualTo("disconnected");
    }
}
