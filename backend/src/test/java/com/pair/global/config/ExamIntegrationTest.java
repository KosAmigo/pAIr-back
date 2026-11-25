package com.pair.global.config;

import org.junit.jupiter.api.Tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.infra.payment.TossPaymentClient;
import com.pair.infra.sandbox.CodeRunner;

/**
 * 개발용 사용자 헤더(pair.auth.mode=header) 통합 테스트의 공통 설정. 이 클래스를 상속한 테스트는 스프링 컨텍스트 하나를 같이 쓴다.
 * 설정이 한 글자라도 다르면 컨텍스트가 새로 뜨므로, 이 테스트들에서 mock 빈·프로퍼티를 따로 늘리지 않는다.
 * 테스트마다 다른 mock 동작은 각 테스트의 @BeforeEach 에서 when(...) 으로 정하고, mock 은 테스트가 끝날 때마다 스프링이 초기화한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
@Tag("slow")
public abstract class ExamIntegrationTest {

    @DynamicPropertySource
    static void sharedProps(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> IntegrationTestSupport.DATA_DIR.toString());
    }

    @MockitoBean protected JavaMailSender mail;
    @MockitoBean protected CodeRunner runner;
    @MockitoBean protected TossPaymentClient toss;
}
