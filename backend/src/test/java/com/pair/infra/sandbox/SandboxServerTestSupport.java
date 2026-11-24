package com.pair.infra.sandbox;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.pair.global.config.PairProperties;
import com.pair.global.config.SandboxServerConfig;

/**
 * 테스트용 샌드박스 서버를 같은 JVM 에서 띄운다 (무작위 포트). Docker 없이 돌 수 있게 실행기는 local 로 둔다.
 * 실제 서버는 mode=docker 로 뜨며, 이 테스트가 확인하는 것은 앱 ↔ 실행 전용 API 사이의 약속이다.
 */
final class SandboxServerTestSupport {

    static final String TOKEN = "test-token";

    private SandboxServerTestSupport() {}

    static ConfigurableApplicationContext start(String token) {
        return new SpringApplicationBuilder(SandboxServerConfig.class).profiles("sandbox-server")
                .run("--server.port=0", "--pair.run.mode=local", "--pair.run.remote-token=" + token,
                        "--pair.run.timeout-seconds=60", "--logging.level.root=WARN");
    }

    static String url(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    static PairProperties props(String url, String token) {
        return new PairProperties(null, null, null, null, new PairProperties.Run(60, null, null, 0, 0, url, token), null, null);
    }
}
