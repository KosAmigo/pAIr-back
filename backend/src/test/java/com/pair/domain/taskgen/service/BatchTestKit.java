package com.pair.domain.taskgen.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.infra.ai.BatchClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 배치 창구 테스트 도구: 손으로 흘리는 시계, 가짜 BatchClient, 메모리 호출 저장소. 실제 AI·네트워크는 쓰지 않는다 */
final class BatchTestKit {

    private BatchTestKit() {}

    static final ObjectMapper JSON = new ObjectMapper();
    static final String EMPTY_REVIEW = "{\"violations\":[]}";

    /** 시간을 손으로 흘린다 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-26T00:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    record Created(String vendor, List<BatchClient.Req> reqs) {}

    /** 배치를 보내고 상태를 보는 곳을 가짜로 바꾼다. answerFor: custom_id → 답(null 이면 결과에 없음) */
    static final class FakeBatch extends BatchClient {
        final List<Created> created = new CopyOnWriteArrayList<>();
        final Set<String> done = ConcurrentHashMap.newKeySet();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final List<String> statusCalls = new CopyOnWriteArrayList<>();
        final Map<String, Created> byId = new ConcurrentHashMap<>();
        volatile Function<String, Answer> answerFor = id -> new Answer(true, EMPTY_REVIEW, 1000, 2000, 0, "stop", null);
        volatile boolean failCreate;
        private final AtomicInteger seq = new AtomicInteger();

        FakeBatch() {
            super(null, null, JSON);
        }

        @Override
        public String create(String vendor, List<Req> reqs) {
            if (failCreate) throw new com.pair.infra.ai.AiUnavailableException("배치를 만들 수 없습니다");
            String id = "batch-" + seq.incrementAndGet();
            Created c = new Created(vendor, List.copyOf(reqs));
            created.add(c);
            byId.put(id, c);
            return id;
        }

        @Override
        public Status status(String vendor, String id) {
            statusCalls.add(id);
            return new Status(id, done.contains(id), null, null, null, "", 0, 0);
        }

        @Override
        public Map<String, Answer> answers(String vendor, Status st) {
            Map<String, Answer> out = new LinkedHashMap<>();
            Created c = byId.get(st.raw());
            if (c != null) for (Req r : c.reqs()) {
                Answer a = answerFor.apply(r.customId());
                if (a != null) out.put(r.customId(), a);
            }
            return out;
        }

        @Override
        public void cancel(String vendor, String id) {
            cancelled.add(id);
        }

        /** 이 id 로 보낸 배치가 끝난 것으로 한다 */
        void finish(String id) {
            done.add(id);
        }
    }

    /** (작업, 해시, nth) 로 찾는 메모리 호출 저장소. save 하면 그대로 들어간다 */
    static final class Calls {
        final Map<String, TaskGenCall> rows = new HashMap<>();
        final TaskGenCallRepository repo = mock(TaskGenCallRepository.class);

        Calls() {
            when(repo.findByJobIdAndInputHashAndNth(anyString(), anyString(), anyInt())).thenAnswer(i -> {
                synchronized (rows) {
                    return Optional.ofNullable(rows.get(key(i.getArgument(0), i.getArgument(1), i.getArgument(2))));
                }
            });
            when(repo.save(any(TaskGenCall.class))).thenAnswer(i -> {
                TaskGenCall c = i.getArgument(0);
                synchronized (rows) {
                    rows.put(key(c.getJobId(), c.getInputHash(), c.getNth()), c);
                }
                return c;
            });
            when(repo.existsByJobIdAndStatus(anyString(), anyString())).thenAnswer(i -> {
                synchronized (rows) {
                    return rows.values().stream().anyMatch(c -> c.getJobId().equals(i.getArgument(0)) && c.getStatus().equals(i.getArgument(1)));
                }
            });
        }

        static String key(String job, String hash, int nth) {
            return job + "|" + hash + "|" + nth;
        }

        List<TaskGenCall> all() {
            synchronized (rows) {
                return new ArrayList<>(rows.values());
            }
        }
    }

    /** BATCH 로 실행 중인 작업 수를 손으로 정하는 작업 저장소 */
    static TaskGenJobRepository jobsRunning(AtomicInteger running) {
        TaskGenJobRepository jobs = mock(TaskGenJobRepository.class);
        when(jobs.countByEnvAndStatusAndMode(anyString(), anyString(), anyString())).thenAnswer(i -> (long) running.get());
        return jobs;
    }

    /** 조건이 될 때까지 짧게 기다린다(가상 스레드가 창구에 맡기기를 기다릴 때) */
    static void await(java.util.function.BooleanSupplier cond) {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("조건이 5초 안에 되지 않았습니다");
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
