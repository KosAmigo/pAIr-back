package com.pair;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.global.config.PackageRules;

/** 패키지 구조·클래스 이름 규칙 검사. 규칙은 PackageRules 한 곳에 있다. ./gradlew archTest 로 이것만 돌릴 수 있다 */
class ArchitectureTest {

    @Test
    @DisplayName("main 코드가 패키지 구조·이름 규칙을 지킨다")
    void packageRules() {
        assertThat(new PackageRules().violations()).as("규칙을 어긴 클래스").isEmpty();
    }
}
