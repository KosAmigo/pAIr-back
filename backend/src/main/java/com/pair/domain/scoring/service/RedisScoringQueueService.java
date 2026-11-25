package com.pair.domain.scoring.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pair.domain.scoring.dto.ScoringJobDto;
import com.pair.global.config.ScoringQueueProperties;

/**
 * Redis 스트림으로 만든 채점 대기열.
 * <pre>
 * {prefix}:jobs          대기·진행 중 작업 (스트림, 소비자 그룹 하나)
 * {prefix}:retry         다시 시도를 기다리는 작업 (정렬 집합, 점수 = 다시 넣을 시각)
 * {prefix}:dead          끝까지 실패한 작업 (스트림, 최근 1000건)
 * {prefix}:active:{id}   이미 대기 중이거나 채점 중인 응시 표시 (중복 방지)
 * </pre>
 * 작업자가 꺼내 가고 끝내지 않은 작업은 스트림의 "처리 중" 목록에 남아서, 서버를 다시 켜도 recoverStale 로 되살린다.
 * Redis 자체가 재시작 때 작업을 잃지 않으려면 운영 Redis 의 영속성(AOF) 설정이 필요하다.
 */
@Service
@ConditionalOnProperty(prefix = "pair.scoring-queue", name = "enabled", havingValue = "true")
public class RedisScoringQueueService implements ScoringQueueService {

    private static final Logger log = LoggerFactory.getLogger(RedisScoringQueueService.class);
    private static final String GROUP = "scoring";
    private static final long DEAD_KEEP = 1000;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final StreamOperations<String, String, String> streams;
    private final String jobs;
    private final String retry;
    private final String dead;
    private final String activePrefix;
    private final Consumer consumer = Consumer.from(GROUP, "worker-" + UUID.randomUUID());
    private volatile boolean groupReady;

    public RedisScoringQueueService(StringRedisTemplate redis, ScoringQueueProperties props) {
        this.redis = redis;
        this.streams = redis.opsForStream();
        this.jobs = props.keyPrefix() + ":jobs";
        this.retry = props.keyPrefix() + ":retry";
        this.dead = props.keyPrefix() + ":dead";
        this.activePrefix = props.keyPrefix() + ":active:";
    }

    @Override
    public boolean enqueue(String attemptId) {
        ensureGroup();
        Boolean first = redis.opsForValue().setIfAbsent(activePrefix + attemptId, "1", ACTIVE_TTL);
        if (!Boolean.TRUE.equals(first)) return false;
        try {
            add(jobs, attemptId, 1);
        } catch (RuntimeException e) {
            redis.delete(activePrefix + attemptId); // 넣지 못했으면 표시도 남기지 않는다
            throw e;
        }
        return true;
    }

    @Override
    public Optional<ScoringJobDto> take(Duration wait) {
        ensureGroup();
        List<MapRecord<String, String, String>> records = streams.read(consumer,
                StreamReadOptions.empty().count(1).block(wait),
                StreamOffset.create(jobs, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return Optional.empty();
        return Optional.of(toJob(records.get(0)));
    }

    @Override
    public void complete(ScoringJobDto job) {
        finish(job);
        redis.delete(activePrefix + job.attemptId());
    }

    @Override
    public void retryLater(ScoringJobDto job, Duration delay) {
        // 다시 시도를 기다리는 동안에도 active 표시를 둔다. 같은 응시가 또 들어와 두 번 채점되지 않게 한다
        long readyAt = Instant.now().plus(delay).toEpochMilli();
        redis.opsForZSet().add(retry, job.attemptId() + "|" + (job.attempt() + 1), readyAt);
        finish(job);
    }

    @Override
    public void fail(ScoringJobDto job, String error) {
        streams.add(StreamRecords.newRecord().in(dead).ofMap(Map.of(
                "attemptId", job.attemptId(),
                "attempt", String.valueOf(job.attempt()),
                "error", error == null ? "" : error.substring(0, Math.min(error.length(), 500)),
                "failedAt", Instant.now().toString())));
        streams.trim(dead, DEAD_KEEP, true);
        finish(job);
        redis.delete(activePrefix + job.attemptId());
    }

    @Override
    public int promoteDue() {
        var due = redis.opsForZSet().rangeByScore(retry, 0, Instant.now().toEpochMilli());
        if (due == null || due.isEmpty()) return 0;
        int moved = 0;
        for (String member : due) {
            // 여러 서버가 동시에 돌아도 remove 가 1 을 돌려준 한 곳만 옮긴다
            Long removed = redis.opsForZSet().remove(retry, member);
            if (removed == null || removed == 0) continue;
            int bar = member.lastIndexOf('|');
            add(jobs, member.substring(0, bar), Integer.parseInt(member.substring(bar + 1)));
            moved++;
        }
        return moved;
    }

    @Override
    public int recoverStale(Duration idle, int maxAttempts) {
        ensureGroup();
        PendingMessages pending = streams.pending(jobs, GROUP, Range.unbounded(), 100);
        if (pending == null) return 0;
        int handled = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(idle) < 0) continue;
            List<MapRecord<String, String, String>> body = streams.range(jobs,
                    Range.closed(message.getId().getValue(), message.getId().getValue()));
            if (body == null || body.isEmpty()) {
                streams.acknowledge(jobs, GROUP, message.getId()); // 내용이 사라진 항목은 정리만 한다
                continue;
            }
            ScoringJobDto job = toJob(body.get(0));
            finish(job);
            if (job.attempt() >= maxAttempts) {
                fail(new ScoringJobDto(job.messageId(), job.attemptId(), job.attempt()), "처리 중 응답이 없어 포기했습니다");
            } else {
                add(jobs, job.attemptId(), job.attempt() + 1);
            }
            log.warn("끝나지 않은 채점 작업을 되살렸습니다: attemptId={} attempt={}", job.attemptId(), job.attempt());
            handled++;
        }
        return handled;
    }

    private void add(String stream, String attemptId, int attempt) {
        streams.add(StreamRecords.newRecord().in(stream).ofMap(Map.of(
                "attemptId", attemptId, "attempt", String.valueOf(attempt))));
    }

    /** 처리 중 목록에서 빼고 스트림에서도 지운다. */
    private void finish(ScoringJobDto job) {
        RecordId id = RecordId.of(job.messageId());
        streams.acknowledge(jobs, GROUP, id);
        streams.delete(jobs, id);
    }

    private static ScoringJobDto toJob(MapRecord<String, String, String> record) {
        Map<String, String> value = record.getValue();
        return new ScoringJobDto(record.getId().getValue(), value.get("attemptId"), Integer.parseInt(value.get("attempt")));
    }

    private void ensureGroup() {
        if (groupReady) return;
        synchronized (this) {
            if (groupReady) return;
            try {
                streams.createGroup(jobs, ReadOffset.from("0"), GROUP);
            } catch (DataAccessException e) {
                if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) throw e;
            }
            groupReady = true;
        }
    }
}
