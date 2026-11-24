package com.pair;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.pair.global.config.PackageRulesConfig;

/** 패키지 구조·클래스 이름 규칙 검사. 규칙은 PackageRulesConfig 한 곳에 있다. ./gradlew archTest 로 이것만 돌릴 수 있다 */
class ArchitectureTest {

    @Test
    @DisplayName("main 코드가 패키지 구조·이름 규칙을 지킨다")
    void packageRules() {
        assertThat(PackageRulesConfig.violations()).as("규칙을 어긴 클래스").isEmpty();
    }

    @Test
    @DisplayName("스프링 컨텍스트를 띄우는 테스트는 @Tag(\"slow\")가 있다 (로컬 build 는 slow 를 뺀다)")
    void springContextTestsAreSlow() throws Exception {
        Path root = Paths.get(ArchitectureTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith("Test.class"))::iterator) {
                String name = root.relativize(f).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                Class<?> c = Class.forName(name, false, ArchitectureTest.class.getClassLoader());
                if (startsSpringContext(c) && !hasSlowTag(c)) bad.add(name);
            }
        }
        assertThat(bad).as("스프링 컨텍스트를 띄우는데 @Tag(\"slow\")가 없는 테스트. 공통 베이스(ExamIntegrationTest, BearerIntegrationTest)를 상속하거나 클래스에 @Tag(\"slow\")를 붙이세요")
                .isEmpty();
    }

    private static boolean startsSpringContext(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (AnnotatedElementUtils.hasAnnotation(k, BootstrapWith.class)) return true;
            ExtendWith ew = AnnotatedElementUtils.findMergedAnnotation(k, ExtendWith.class);
            if (ew != null && List.of(ew.value()).contains(SpringExtension.class)) return true;
        }
        return false;
    }

    private static boolean hasSlowTag(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (AnnotatedElementUtils.getMergedRepeatableAnnotations(k, Tag.class).stream().anyMatch(t -> "slow".equals(t.value()))) return true;
        }
        return c.getEnclosingClass() != null && hasSlowTag(c.getEnclosingClass());
    }
}
