package com.pair.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PairPropertiesTest {

    @Test
    void relativeDirs_resolveFromRepoRoot() {
        // 저장소 최상위(settings.gradle 과 grader 가 함께 있는 곳) 기준으로 바뀐다
        PairProperties p = new PairProperties("pilot-data", null, null, null, null, new PairProperties.Grader("grader", 4), null);
        Path grader = Path.of(p.grader().dir());
        assertTrue(Files.isDirectory(grader.resolve("samples")), grader.toString());
        assertTrue(Files.isRegularFile(grader.getParent().resolve("settings.gradle")));
        assertEquals(grader.getParent(), Path.of(p.dataDir()).getParent());
    }

    @Test
    void absoluteDir_isKept() {
        String abs = Path.of("x").toAbsolutePath().toString();
        assertEquals(abs, PairProperties.fromRepoRoot(abs));
    }
}
