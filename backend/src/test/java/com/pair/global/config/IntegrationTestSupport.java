package com.pair.global.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 통합 테스트가 같이 쓰는 임시 자료 폴더. 테스트 JVM 하나에 폴더 하나를 만들고 JVM 이 끝나면 지운다.
 * 테스트마다 폴더가 달라 스프링 컨텍스트를 매번 새로 띄우던 것을, 폴더를 하나로 맞춰 컨텍스트를 다시 쓰게 한다.
 * 파일 경로에 attempt id·과제 코드 같은 고유 값이 들어가므로 테스트끼리 섞이지 않는다.
 */
final class IntegrationTestSupport {

    static final Path DATA_DIR = create();

    private IntegrationTestSupport() {}

    private static Path create() {
        try {
            Path dir = Files.createTempDirectory("pair-test-data");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더라 못 지워도 괜찮다
        }
    }
}
