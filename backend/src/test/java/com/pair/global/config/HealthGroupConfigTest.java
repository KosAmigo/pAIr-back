package com.pair.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;

/**
 * 배포 확인(deploy.yml)이 보는 core 상태 그룹: Redis(채점 큐)만 빼고 DB 등 나머지는 모두 본다.
 * Redis 가 잠깐 죽어도 배포가 막히지 않게 하려는 것이다 (전체 /actuator/health 는 Redis 까지 본다).
 */
class HealthGroupConfigTest extends ExamIntegrationTest {

    @Autowired HealthEndpointGroups groups;

    @Test
    void coreGroupExcludesOnlyRedis() {
        HealthEndpointGroup core = groups.get("core");
        assertThat(core).as("application.yml 의 management.endpoint.health.group.core").isNotNull();
        assertThat(core.isMember("redis")).isFalse();
        assertThat(core.isMember("db")).isTrue();
        assertThat(core.isMember("diskSpace")).isTrue();
        // 전체 상태(기본 그룹)는 Redis 도 본다
        assertThat(groups.getPrimary().isMember("redis")).isTrue();
    }
}
