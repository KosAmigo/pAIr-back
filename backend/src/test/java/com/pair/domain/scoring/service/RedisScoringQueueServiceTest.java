package com.pair.domain.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pair.domain.scoring.dto.ScoringJobDto;
import com.pair.global.config.ScoringQueueProperties;

/**
 * 실제 Redis 로 채점 큐를 확인한다. 로컬 Redis(docker compose --profile redis up -d)가 없으면 건너뛴다.
 * 스프링 컨텍스트를 띄우지 않고, 테스트마다 따로 만든 키 접두어를 쓰므로 다른 테스트와 겹치지 않는다.
 */
@Tag("slow")
class RedisScoringQueueServiceTest {

    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisScoringQueueService queue;
    String prefix;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("PAIR_REDIS_HOST", "localhost");
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, 6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        boolean reachable = true;
        try {
            factory.getConnection().ping();
        } catch (RuntimeException e) {
            reachable = false;
        }
        if (!reachable) {
            factory.destroy();
            redis = null;
        }
        assumeTrue(reachable, "Redis 에 연결할 수 없어 건너뜁니다");
        prefix = "pair-test:" + UUID.randomUUID();
        queue = new RedisScoringQueueService(redis, new ScoringQueueProperties(true, 1, 3, null, null, prefix));
    }

    @AfterEach
    void tearDown() {
        if (redis == null) return;
        Set<String> keys = redis.keys(prefix + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        factory.destroy();
    }

    private ScoringJobDto take() {
        return queue.take(Duration.ofSeconds(1)).orElseThrow();
    }

    @Test
    @DisplayName("넣은 작업을 꺼내고, 끝내면 같은 응시를 다시 넣을 수 있다")
    void enqueueTakeComplete() {
        assertThat(queue.enqueue("7")).isTrue();
        ScoringJobDto job = take();
        assertThat(job.attemptId()).isEqualTo("7");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(queue.take(Duration.ofMillis(100))).isEmpty();

        queue.complete(job);
        assertThat(queue.enqueue("7")).isTrue();
    }

    @Test
    @DisplayName("대기 중이거나 채점 중인 응시는 두 번 넣어지지 않는다")
    void rejectsDuplicates() {
        assertThat(queue.enqueue("8")).isTrue();
        assertThat(queue.enqueue("8")).isFalse();
        ScoringJobDto job = take();
        assertThat(queue.enqueue("8")).isFalse(); // 채점 중
        queue.complete(job);
    }

    @Test
    @DisplayName("다시 시도할 작업은 시각이 되기 전에는 나오지 않고, 되면 시도 횟수 +1 로 돌아온다")
    void retryLaterThenPromote() {
        queue.enqueue("9");
        ScoringJobDto first = take();

        queue.retryLater(first, Duration.ofHours(1));
        assertThat(queue.promoteDue()).isZero();
        assertThat(queue.take(Duration.ofMillis(100))).isEmpty();
        assertThat(queue.enqueue("9")).isFalse(); // 다시 시도를 기다리는 동안에도 중복으로 넣지 않는다

        redis.opsForZSet().add(prefix + ":retry", "9|2", 0); // 시각이 지난 것으로 만든다
        assertThat(queue.promoteDue()).isPositive();
        ScoringJobDto second = take();
        assertThat(second.attemptId()).isEqualTo("9");
        assertThat(second.attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("끝까지 실패한 작업은 실패 보관함에 남고, 같은 응시를 다시 넣을 수 있다")
    void failKeepsDeadLetter() {
        queue.enqueue("10");
        ScoringJobDto job = take();
        queue.fail(job, "api down");

        assertThat(redis.opsForStream().size(prefix + ":dead")).isEqualTo(1);
        assertThat(queue.take(Duration.ofMillis(100))).isEmpty();
        assertThat(queue.enqueue("10")).isTrue();
    }

    @Test
    @DisplayName("꺼낸 뒤 끝나지 않은 작업은 되살리고, 최대 시도를 넘으면 실패 보관함으로 보낸다")
    void recoverStale() {
        queue.enqueue("11");
        take(); // 작업자가 꺼내 갔다가 죽은 것으로 본다 (끝내지 않음)

        assertThat(queue.recoverStale(Duration.ofHours(1), 3)).isZero(); // 아직 오래 걸린 게 아님
        assertThat(queue.recoverStale(Duration.ZERO, 3)).isEqualTo(1);
        ScoringJobDto second = take();
        assertThat(second.attempt()).isEqualTo(2);

        assertThat(queue.recoverStale(Duration.ZERO, 2)).isEqualTo(1); // 2번째가 한도
        assertThat(queue.take(Duration.ofMillis(100))).isEmpty();
        assertThat(redis.opsForStream().size(prefix + ":dead")).isEqualTo(1);
        assertThat(queue.enqueue("11")).isTrue();
    }
}
