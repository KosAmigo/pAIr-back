package com.pair.global.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 샌드박스 서버의 시작점. 앱 서버(PairApplication)와 같은 jar 에서 따로 띄우되, 코드 실행에 필요한 것만 읽는다.
 * DB·결제·AI·메일 설정이 없어도 켜지고, 그래서 이 서버에는 DB 접속 정보와 API 키를 두지 않는다.
 * 실행: {@code gradlew bootRunSandbox}, 또는 jar 에서
 * {@code java -Dloader.main=com.pair.global.config.SandboxServerConfig -cp pair.jar org.springframework.boot.loader.launch.PropertiesLauncher}.
 * 필요한 환경변수: PAIR_RUN_REMOTE_TOKEN (앱 서버와 같은 값). 포트는 8282(PAIR_SANDBOX_PORT).
 * 프로필 sandbox-server 일 때만 설정으로 읽히므로 일반 앱 서버에는 영향이 없다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("sandbox-server")
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class})
@ComponentScan(basePackages = {"com.pair.infra.sandbox", "com.pair.domain.sandbox", "com.pair.global.error"})
@EnableConfigurationProperties(PairProperties.class)
public class SandboxServerConfig {

    public static void main(String[] args) {
        new SpringApplicationBuilder(SandboxServerConfig.class).profiles("sandbox-server").run(args);
    }
}
