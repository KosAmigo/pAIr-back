package com.pair.infra.sandbox;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.sun.net.httpserver.HttpServer;

/** 실행 전용 API 의 인증과 오류 처리. 실행기는 가볍게 local 이라 Docker 가 없어도 돈다 */
@Tag("slow")
class RemoteDockerRunnerProtocolTest {

    private static ConfigurableApplicationContext server;

    @BeforeAll
    static void startServer() {
        server = SandboxServerTestSupport.start(SandboxServerTestSupport.TOKEN);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static final String HELLO = """
            package shop;
            public class Hello { public int one() { return 1; } }
            """;

    private HttpResponse<String> post(String path, String auth, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SandboxServerTestSupport.url(server) + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (auth != null) b.header("Authorization", auth);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void buildAndRun_workWithRightToken() {
        var runner = new RemoteDockerRunner(SandboxServerTestSupport.props(SandboxServerTestSupport.url(server), SandboxServerTestSupport.TOKEN));
        var build = runner.build(Map.of("src/shop/Hello.java", HELLO));
        assertTrue(build.compiled());
        var run = runner.run(Map.of("src/shop/Hello.java", HELLO));
        assertTrue(run.compiled());
    }

    @Test
    void compileError_comesBackWithLocation() {
        var runner = new RemoteDockerRunner(SandboxServerTestSupport.props(SandboxServerTestSupport.url(server), SandboxServerTestSupport.TOKEN));
        var build = runner.build(Map.of("src/shop/Hello.java", "package shop; public class Hello { int x = ; }"));
        assertEquals(false, build.compiled());
        assertEquals("src/shop/Hello.java", build.diagnostics().get(0).file());
    }

    @Test
    void missingOrWrongToken_isRejectedWith401() throws Exception {
        String body = "{\"sources\":{\"src/shop/Hello.java\":\"x\"}}";
        assertEquals(401, post("/internal/run", null, body).statusCode());
        assertEquals(401, post("/internal/run", "Bearer wrong", body).statusCode());
        assertEquals(401, post("/internal/build", "Bearer wrong", body).statusCode());
        assertEquals(401, post("/internal/run", SandboxServerTestSupport.TOKEN, body).statusCode()); // Bearer 접두사 없음
    }

    @Test
    void missingSources_is400() throws Exception {
        assertEquals(400, post("/internal/run", "Bearer " + SandboxServerTestSupport.TOKEN, "{}").statusCode());
    }

    @Test
    void wrongToken_onAppSide_isServerErrorNotBusy() {
        var runner = new RemoteDockerRunner(SandboxServerTestSupport.props(SandboxServerTestSupport.url(server), "wrong"));
        var e = assertThrows(UncheckedIOException.class, () -> runner.build(Map.of("src/shop/Hello.java", HELLO)));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void serverBusy_503_becomesBusyException() throws Exception {
        HttpServer fake = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fake.createContext("/internal/run", ex -> {
            byte[] b = "{\"message\":\"실행 요청이 많습니다\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(503, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fake.start();
        try {
            var runner = new RemoteDockerRunner(SandboxServerTestSupport.props("http://localhost:" + fake.getAddress().getPort(), "t"));
            var e = assertThrows(SandboxRunner.BusyException.class, () -> runner.run(Map.of("src/shop/Hello.java", HELLO)));
            assertEquals("실행 요청이 많습니다", e.getMessage());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void serverDown_becomesBusyException() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        var runner = new RemoteDockerRunner(SandboxServerTestSupport.props("http://localhost:" + freePort, "t"));
        var e = assertThrows(SandboxRunner.BusyException.class, () -> runner.run(Map.of("src/shop/Hello.java", HELLO)));
        assertTrue(e.getMessage().contains("연결하지 못했습니다"));
    }

    @Test
    void remoteMode_requiresUrlAndToken() {
        assertThrows(IllegalStateException.class, () -> new RemoteDockerRunner(SandboxServerTestSupport.props("", "t")));
        assertThrows(IllegalStateException.class, () -> new RemoteDockerRunner(SandboxServerTestSupport.props("http://x", " ")));
    }

    @Test
    void sandboxServer_refusesToStartWithoutToken() {
        assertThrows(Exception.class, () -> SandboxServerTestSupport.start("").close());
    }
}
