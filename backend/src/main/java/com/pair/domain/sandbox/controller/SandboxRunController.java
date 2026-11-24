package com.pair.domain.sandbox.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.sandbox.dto.SandboxSourcesRequest;
import com.pair.global.config.PairProperties;
import com.pair.global.error.UnauthorizedException;
import com.pair.infra.sandbox.CodeRunner;

/**
 * 샌드박스 서버의 실행 전용 API. 앱 서버의 {@code RemoteDockerRunner} 만 부른다(공유 토큰).
 * 프로필 {@code sandbox-server} 로 켠 샌드박스 서버에서만 만들어지고, 일반 앱 서버에는 이 주소가 없다.
 * 토큰이 비어 있으면 인증 없는 코드 실행 서버가 되므로 서버가 시작되지 않는다.
 */
@RestController
@Profile("sandbox-server")
@RequestMapping("/internal")
public class SandboxRunController {

    private final CodeRunner runner;
    private final byte[] expected;

    public SandboxRunController(CodeRunner runner, PairProperties props) {
        String token = props.run() == null ? null : props.run().remoteToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("샌드박스 서버에는 공유 토큰(pair.run.remote-token, PAIR_RUN_REMOTE_TOKEN)이 필요합니다.");
        }
        this.runner = runner;
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/run")
    public CodeRunner.RunResult run(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody SandboxSourcesRequest req) {
        authorize(auth);
        return runner.run(sources(req));
    }

    @PostMapping("/build")
    public CodeRunner.BuildResult build(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody SandboxSourcesRequest req) {
        authorize(auth);
        return runner.build(sources(req));
    }

    private void authorize(String auth) {
        // 시간 차이로 토큰을 알아내지 못하게 상수 시간 비교
        if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expected)) {
            throw new UnauthorizedException("샌드박스 서버 토큰이 올바르지 않습니다.");
        }
    }

    private static java.util.Map<String, String> sources(SandboxSourcesRequest req) {
        if (req.sources() == null) throw new IllegalArgumentException("sources 가 없습니다.");
        return req.sources();
    }
}
