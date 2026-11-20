package com.pair.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 테스트(examtest 프로필)에서는 AI 키가 비어 있어야 한다.
 * application.yml 이 application-secret.yml 을 읽어 와도, 빌드할 때마다 실제 API 가 불려 과금되는 일이 없게 막는다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
class TestAiKeysBlankTest {

    @MockitoBean JavaMailSender mail;

    @Autowired AiKeys keys;

    @Test
    void 테스트에서는_AI_키가_비어_있다() {
        assertThat(keys.openai()).isBlank();
        assertThat(keys.anthropic()).isBlank();
    }
}
