package com.pair.infra.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import com.pair.global.config.PairProperties;

/** 서버가 죽어도 실행 컨테이너가 남지 않는지: 시작할 때 남은 것을 지우고, 컨테이너도 스스로 끝난다. Docker 와 pair-sandbox 이미지가 있을 때만 돈다. */
@Tag("slow") // 실제 Docker 로 컨테이너를 만들고 수십 초 기다린다
@EnabledIf("com.pair.infra.sandbox.SandboxRunnerTest#dockerReady")
class SandboxCleanupTest {

    private static String docker(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        return out.strip();
    }

    private static boolean exists(String name) throws Exception {
        return !docker("ps", "-aq", "--filter", "name=^" + name + "$").isEmpty();
    }

    /** 다른 테스트가 세는 pair-run-* 와 겹치지 않는 이름을 쓴다(테스트는 병렬로 돌고 Docker 를 같이 쓴다) */
    private static final String PREFIX = "pair-cleanup-test-";

    private static SandboxRunner runner(int timeoutSeconds) {
        return new SandboxRunner(new PairProperties(null, null, null, null, new PairProperties.Run(timeoutSeconds), null, null), PREFIX);
    }

    @Test
    void 시작할_때_남은_실행_컨테이너만_지우고_다른_컨테이너는_둔다() throws Exception {
        String leftover = PREFIX + "leftover-" + UUID.randomUUID();
        String other = "other-keep-" + UUID.randomUUID();
        try {
            docker("run", "-d", "--name", leftover, "--entrypoint", "sleep", "pair-sandbox", "300");
            docker("run", "-d", "--name", other, "--entrypoint", "sleep", "pair-sandbox", "300");
            assertThat(exists(leftover)).isTrue();

            runner(60).removeLeftoverContainers();

            assertThat(exists(leftover)).as("남은 실행 컨테이너").isFalse();
            assertThat(exists(other)).as("실행 컨테이너가 아닌 것은 그대로").isTrue();
        } finally {
            docker("rm", "-f", leftover, other);
        }
    }

    @Test
    void 서버가_죽어도_컨테이너는_정해진_시간_뒤_스스로_끝난다(@TempDir Path work) throws Exception {
        // 제한 1초 → 컨테이너 안 제한은 1 + 시작 여유 15 + 5 = 21초
        SandboxRunner runner = runner(1);
        Path src = work.resolve("src");
        Path file = src.resolve("shop/LoopTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package shop;
                import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
                class LoopTest { @Test void loop() { while (true) { } } }
                """);
        if (Files.getFileStore(src).supportsFileAttributeView("posix")) {
            for (Path p : new Path[] {work, src, file.getParent()}) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        String name = PREFIX + "orphan-" + UUID.randomUUID();
        Process cli = new ProcessBuilder(runner.dockerRunCommand(name, "@@PAIR-test@@", src.toRealPath(), "run")).redirectErrorStream(true).start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!exists(name) && System.nanoTime() < until) Thread.sleep(300);
            assertThat(exists(name)).as("컨테이너가 떠 있다").isTrue();

            cli.destroyForcibly(); // 서버(docker 를 부른 프로세스)가 죽은 상황
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (exists(name) && System.nanoTime() < deadline) Thread.sleep(1000);

            assertThat(exists(name)).as("무한 루프 컨테이너가 서버 없이도 사라진다").isFalse();
        } finally {
            cli.destroyForcibly();
            docker("rm", "-f", name);
        }
    }
}
