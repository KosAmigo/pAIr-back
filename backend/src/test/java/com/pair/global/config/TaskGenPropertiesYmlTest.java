package com.pair.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** application.yml 의 pair.taskgen 설정이 TaskGenProperties 로 읽히는지 확인한다(스프링 서버를 띄우지 않고 파일만 읽는다) */
class TaskGenPropertiesYmlTest {

    private static TaskGenProperties load() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources all = new MutablePropertySources();
        sources.forEach(all::addLast);
        return new Binder(ConfigurationPropertySources.from(all), new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(all)).bind("pair.taskgen", TaskGenProperties.class).get();
    }

    @Test
    @DisplayName("직군마다 단계 모델: 모두 sol 이 생성, DEV 관문은 sol·답 노출 2차 Sonnet, BIZ 관문은 Sonnet·2차 terra, 모두 medium. BIZ 직접 풀이는 Sonnet")
    void stagesAndSolverFromYml() throws Exception {
        TaskGenProperties p = load();
        String sonnet = "claude-sonnet-5-5", sol = "gpt-6.1-sol", terra = "gpt-6.1-terra";
        Object[][] dev = {{"BLUEPRINT", sol}, {"WRITING", sol}, {"CODE", sol}, {"ANSWER", sol}, {"REVIEW", sol}, {"REVIEW2", sonnet}};
        Object[][] biz = {{"BLUEPRINT", sol}, {"WRITING", sol}, {"QUESTIONS", sol}, {"ANSWER", sol}, {"REVIEW", sonnet}, {"REVIEW2", terra}};
        for (Object[] r : dev) {
            assertThat(p.stage(true, (String) r[0]).model()).as("DEV " + r[0]).isEqualTo(r[1]);
            assertThat(p.stage(true, (String) r[0]).effort()).as("DEV " + r[0]).isEqualTo("medium");
        }
        for (Object[] r : biz) {
            assertThat(p.stage(false, (String) r[0]).model()).as("BIZ " + r[0]).isEqualTo(r[1]);
            assertThat(p.stage(false, (String) r[0]).effort()).as("BIZ " + r[0]).isEqualTo("medium");
        }
        assertThat(p.stage(true, "REVIEW").maxTokens()).isEqualTo(16000);
        assertThat(p.stage(false, "QUESTIONS").maxTokens()).isEqualTo(8000);
        assertThat(p.stage(true, "BLUEPRINT").maxTokens()).isEqualTo(24000);
        assertThat(p.stage(true, "ANSWER").maxTokens()).isEqualTo(32000);
        assertThat(p.costCap()).isEqualTo(0.6);
        assertThat(p.cost("gpt-6.1-sol", 1_000_000, 1_000_000)).isEqualTo(12.0);
        assertThat(p.solverModel()).isEqualTo(sonnet);
        assertThat(p.solverEffort()).isEqualTo("medium");
        assertThat(p.staged()).isTrue();
        // 그대로 두기로 한 값
        assertThat(p.suggestModel()).isEqualTo("gpt-6-luna");
        assertThat(p.leakModel()).isEqualTo("gpt-6-luna");
    }
}
