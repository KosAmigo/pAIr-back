package com.pair.infra.sandbox;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.context.ConfigurableApplicationContext;

/** 원격 실행기도 로컬·Docker 실행기와 같은 계약을 지켜야 한다 (앱 → HTTP → 샌드박스 서버 → 실행기) */
@Tag("slow")
class RemoteDockerRunnerTest extends CodeRunnerContractTest {

    private static ConfigurableApplicationContext server;

    @BeforeAll
    static void startServer() {
        server = SandboxServerTestSupport.start(SandboxServerTestSupport.TOKEN);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Override
    CodeRunner createRunner() {
        return new RemoteDockerRunner(SandboxServerTestSupport.props(SandboxServerTestSupport.url(server), SandboxServerTestSupport.TOKEN));
    }
}
