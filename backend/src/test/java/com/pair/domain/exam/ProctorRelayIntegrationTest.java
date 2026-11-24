package com.pair.domain.exam;

import com.pair.domain.exam.service.RelayAuthService;
import com.pair.domain.exam.service.RelayLimitService;
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
import com.pair.domain.exam.service.AttemptSubmitService;

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
    @MockitoBean RelayAuthService authorizer;
    @MockitoBean AttemptSubmitService attempts;
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
    @DisplayName("PC 가 보낸 화질 모드(watch|scan)만 휴대폰으로 전달하고, 기본은 watch 다")
    void forwardsQualityFromPcOnly() throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        String code = nextJson(pc).get("pairCode").asText();
        nextJson(pc);
        Client phone = connect();
        phone.session.sendMessage(new TextMessage("{\"type\":\"hello-phone\",\"pairCode\":\"" + code + "\"}"));
        assertThat(nextJson(phone).get("type").asText()).isEqualTo("phone-joined");
        JsonNode initial = nextJson(phone);
        assertThat(initial.get("type").asText()).isEqualTo("quality");
        assertThat(initial.get("mode").asText()).isEqualTo("watch"); // 기본값
        nextJson(pc); // peer connected

        pc.session.sendMessage(new TextMessage("{\"type\":\"quality\",\"mode\":\"turbo\"}")); // 모르는 값은 무시
        phone.session.sendMessage(new TextMessage("{\"type\":\"quality\",\"mode\":\"scan\"}")); // 휴대폰 발은 무시
        pc.session.sendMessage(new TextMessage("{\"type\":\"quality\",\"mode\":\"scan\"}"));
        JsonNode forwarded = nextJson(phone);
        assertThat(forwarded.get("mode").asText()).isEqualTo("scan");
        assertThat(pc.got.poll(300, TimeUnit.MILLISECONDS)).isNull(); // PC 로 되돌아오는 것도 없다
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
        phone.session.sendMessage(new BinaryMessage(ByteBuffer.wrap(new byte[RelayLimitService.MAX_FRAME_BYTES + 1])));
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

    // ── 감독관 실시간 보기 (시그널링만 중계, 영상은 서버를 지나지 않는다) ──

    @MockitoBean com.pair.domain.exam.service.ViewerAccessService viewerAccess;

    /** 이 테스트의 응시자(대상자) 번호. 짝은 서버 메모리에 남으므로 테스트마다 다르게 한다 */
    private long participantId() {
        return 1000L + SEQ.get();
    }

    private Client connectPc(long sessionId, long participantId) throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        when(authorizer.identify(TOKEN)).thenReturn(java.util.Optional.of(new com.pair.domain.exam.dto.RelayIdentityDto(sessionId, participantId)));
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        assertThat(nextJson(pc).get("type").asText()).isEqualTo("paired");
        assertThat(nextJson(pc).get("type").asText()).isEqualTo("peer");
        return pc;
    }

    /**
     * 감독관이 이미 열려 있는 짝에 PC 가 (다시) 붙는 경우. paired 다음에 peer 와 viewer-request 3개가 오는데,
     * peer 는 전송 스레드로, viewer-request 는 바로 나가므로 서로의 순서는 보장되지 않는다. 프런트도 순서에 기대지 않는다.
     */
    private Client connectPcWithViewers(long sessionId, long participantId) throws Exception {
        when(authorizer.authorize(TOKEN)).thenReturn(true);
        when(authorizer.identify(TOKEN)).thenReturn(java.util.Optional.of(new com.pair.domain.exam.dto.RelayIdentityDto(sessionId, participantId)));
        Client pc = connect();
        pc.session.sendMessage(new TextMessage("{\"type\":\"hello-pc\",\"invite\":\"" + TOKEN + "\"}"));
        assertThat(nextJson(pc).get("type").asText()).isEqualTo("paired");
        java.util.List<String> types = new java.util.ArrayList<>();
        java.util.Set<String> channels = new java.util.TreeSet<>();
        for (int i = 0; i < 4; i++) {
            JsonNode m = nextJson(pc);
            types.add(m.get("type").asText());
            if ("viewer-request".equals(m.get("type").asText())) channels.add(m.get("channel").asText());
        }
        assertThat(types).containsExactlyInAnyOrder("peer", "viewer-request", "viewer-request", "viewer-request");
        assertThat(channels).containsExactly("phone", "screen", "webcam");
        return pc;
    }

    private Client openViewer(long sessionId, long participantId) throws Exception {
        Client viewer = connect();
        viewer.session.sendMessage(new TextMessage("{\"type\":\"watch\",\"sessionId\":" + sessionId + ",\"participantId\":" + participantId
                + ",\"auth\":{\"Authorization\":\"Bearer test\"}}"));
        return viewer;
    }

    private void allow(long sessionId, long participantId) {
        when(viewerAccess.check(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(participantId))).thenReturn(com.pair.domain.exam.dto.ViewerAccessDto.allowed(5L));
    }

    /** 응시자 PC 가 받는 viewer-request 세 개(채널마다)를 읽고 viewer 번호를 돌려준다 */
    private String readViewerRequests(Client pc) throws Exception {
        String viewer = null;
        java.util.Set<String> channels = new java.util.TreeSet<>();
        for (int i = 0; i < 3; i++) {
            JsonNode m = nextJson(pc);
            assertThat(m.get("type").asText()).isEqualTo("viewer-request");
            viewer = m.get("viewer").asText();
            channels.add(m.get("channel").asText());
        }
        assertThat(channels).containsExactly("phone", "screen", "webcam");
        return viewer;
    }

    @Test
    @DisplayName("감독관이 열면 응시자 PC 에 채널마다 영상 요청이 가고, 시그널링은 서로에게만 전달되며, 닫으면 viewer-gone 이 간다")
    void viewerFlow() throws Exception {
        long sid = 7, pid = participantId();
        allow(sid, pid);
        Client pc = connectPc(sid, pid);
        Client viewer = openViewer(sid, pid);

        assertThat(nextJson(viewer).get("state").asText()).isEqualTo("watching");
        String viewerId = readViewerRequests(pc);

        pc.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"webcam\",\"viewer\":\"" + viewerId
                + "\",\"signal\":{\"kind\":\"offer\",\"sdp\":\"v=0\"}}"));
        JsonNode offer = nextJson(viewer);
        assertThat(offer.get("type").asText()).isEqualTo("view-rtc");
        assertThat(offer.get("channel").asText()).isEqualTo("webcam");
        assertThat(offer.get("signal").get("kind").asText()).isEqualTo("offer");

        viewer.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"webcam\",\"signal\":{\"kind\":\"answer\",\"sdp\":\"v=0\"}}"));
        JsonNode answer = nextJson(pc);
        assertThat(answer.get("viewer").asText()).isEqualTo(viewerId); // 서버가 감독관 번호를 붙인다
        assertThat(answer.get("signal").get("kind").asText()).isEqualTo("answer");

        viewer.session.close();
        JsonNode gone = nextJson(pc);
        assertThat(gone.get("type").asText()).isEqualTo("viewer-gone");
        assertThat(gone.get("viewer").asText()).isEqualTo(viewerId);
    }

    @Test
    @DisplayName("권한이 없으면 denied 를 받고 응시자 PC 에는 아무 요청도 가지 않는다")
    void viewerDenied() throws Exception {
        long sid = 8, pid = participantId();
        when(viewerAccess.check(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(sid), org.mockito.ArgumentMatchers.eq(pid)))
                .thenReturn(com.pair.domain.exam.dto.ViewerAccessDto.denied());
        Client pc = connectPc(sid, pid);
        Client viewer = openViewer(sid, pid);

        JsonNode state = nextJson(viewer);
        assertThat(state.get("state").asText()).isEqualTo("denied");
        assertThat(pc.got.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("한 응시자를 동시에 2명까지만 볼 수 있고, 세 번째 감독관은 full 을 받는다")
    void viewerFull() throws Exception {
        long sid = 9, pid = participantId();
        allow(sid, pid);
        connectPc(sid, pid);
        Client first = openViewer(sid, pid);
        Client second = openViewer(sid, pid);
        Client third = openViewer(sid, pid);

        assertThat(nextJson(first).get("state").asText()).isEqualTo("watching");
        assertThat(nextJson(second).get("state").asText()).isEqualTo("watching");
        assertThat(nextJson(third).get("state").asText()).isEqualTo("full");

        first.session.close();
        Thread.sleep(300); // 서버가 닫힘을 처리할 시간
        Client fourth = openViewer(sid, pid);
        assertThat(nextJson(fourth).get("state").asText()).isEqualTo("watching"); // 한 명이 나가면 다시 들어올 수 있다
    }

    @Test
    @DisplayName("응시자 PC 가 끊기면 감독관은 offline 을 받고, PC 가 다시 붙으면 watching 과 영상 요청이 다시 온다")
    void pcReconnect() throws Exception {
        long sid = 10, pid = participantId();
        allow(sid, pid);
        Client pc = connectPc(sid, pid);
        Client viewer = openViewer(sid, pid);
        assertThat(nextJson(viewer).get("state").asText()).isEqualTo("watching");
        readViewerRequests(pc);

        pc.session.close();
        assertThat(nextJson(viewer).get("state").asText()).isEqualTo("offline");

        Client again = connectPcWithViewers(sid, pid);
        assertThat(nextJson(viewer).get("state").asText()).isEqualTo("watching");
    }

    @Test
    @DisplayName("방향에 맞지 않는 신호(감독관의 offer)와 모르는 채널은 전달하지 않는다")
    void viewerSignalDirection() throws Exception {
        long sid = 11, pid = participantId();
        allow(sid, pid);
        Client pc = connectPc(sid, pid);
        Client viewer = openViewer(sid, pid);
        nextJson(viewer);
        readViewerRequests(pc);

        viewer.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"webcam\",\"signal\":{\"kind\":\"offer\",\"sdp\":\"v=0\"}}"));
        viewer.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"audio\",\"signal\":{\"kind\":\"request\"}}"));
        viewer.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"screen\",\"signal\":{\"kind\":\"request\"}}"));

        JsonNode next = nextJson(pc); // 위의 두 개는 버려지고 request 하나만 온다
        assertThat(next.get("channel").asText()).isEqualTo("screen");
        assertThat(next.get("signal").get("kind").asText()).isEqualTo("request");
    }

    @Test
    @DisplayName("응시자 PC 가 모르는 viewer 번호로 보낸 신호는 아무에게도 가지 않는다")
    void pcSignalToUnknownViewer() throws Exception {
        long sid = 12, pid = participantId();
        allow(sid, pid);
        Client pc = connectPc(sid, pid);
        Client viewer = openViewer(sid, pid);
        nextJson(viewer);
        readViewerRequests(pc);

        pc.session.sendMessage(new TextMessage("{\"type\":\"view-rtc\",\"channel\":\"webcam\",\"viewer\":\"v999999\",\"signal\":{\"kind\":\"offer\",\"sdp\":\"v=0\"}}"));
        assertThat(viewer.got.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("연습(사전 테스트) 짝은 감독관이 열 수 없다: 응시자 번호가 없어 offline")
    void practicePairNotWatchable() throws Exception {
        long sid = 13, pid = participantId();
        allow(sid, pid);
        Client viewer = openViewer(sid, pid);
        assertThat(nextJson(viewer).get("state").asText()).isEqualTo("offline"); // 이 응시자의 PC 가 붙은 적이 없다
    }
}
