package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.TaskGenBatchProperties;
import com.pair.infra.ai.BatchClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 창구(설계 7장). BATCH 작업의 모델 호출을 모아 회사(OpenAI/Anthropic)별 배치 하나로 보내고, 결과가 오면 기다리던 스레드를 깨운다.
 * - submit: 호출 스레드(가상 스레드)가 맡기고 결과가 올 때까지 기다린다. 결과 대신 "바로 호출로 바꾸라"는 답(fallback)이 올 수도 있다.
 * - 보내는 때: (a) 내 env 에서 BATCH 로 RUNNING 인 작업이 모두 창구에 호출을 맡겼을 때, (b) 가장 오래 기다린 요청이 hold(60초)가 됐을 때.
 * - 보낸 배치는 poll(30초)마다 확인한다. 끝나면 결과를 나눠 주고, 실패·만료·빠진 요청은 바로 호출로 바꾼다.
 * - 한 요청이 단계 대기 상한(생성 20분, 관문 10분)을 넘으면 그 요청만 빼고 바로 호출로 바꾼다. 그 배치에 남은 요청이 없으면 배치를 취소한다.
 * - 호출은 맡기는 순간 task_gen_calls 에 PENDING(via=BATCH)으로 적고, 보낼 때 배치 id 를 적는다. 서버를 다시 켠 뒤 같은 호출이 배치 id 와 함께
 *   PENDING 으로 남아 있으면 새로 보내지 않고 그 배치를 다시 확인한다.
 * 상태 바꾸기는 tick 한 곳에서만 한다(스케줄러 스레드 하나).
 */
@Component
public class BatchCallService {

    private static final Logger log = LoggerFactory.getLogger(BatchCallService.class);

    /** 바로 호출로 바꾸는 이유(report 의 directReason) */
    public static final String WAIT = "wait", DEADLINE = "deadline", LENGTH = "length", FAILED = "failed";

    /** 맡길 호출 하나. review: 관문·점검 단계(대기 상한이 짧다) */
    public record Request(String jobId, String stage, String label, String hash, int nth, String model, String system, String user,
            Class<?> type, int maxTokens, String effort, boolean review) {}

    /** 결과: fallback 이 null 이면 value 가 답. 아니면 바로 호출로 바꿔야 하는 이유(토큰은 버려진 배치 호출 몫) */
    public record Reply(Object value, long inputTokens, long outputTokens, long cachedInputTokens, long waitedMs, String fallback) {
        public boolean answered() {
            return fallback == null;
        }
    }

    private static final class Entry {
        final String id;
        final Request req;
        final String vendor;
        final Instant enqueuedAt;
        final Duration limit;
        final CompletableFuture<Reply> future = new CompletableFuture<>();
        volatile String batchId;
        volatile boolean finished;

        Entry(String id, Request req, String vendor, Instant enqueuedAt, Duration limit) {
            this.id = id;
            this.req = req;
            this.vendor = vendor;
            this.enqueuedAt = enqueuedAt;
            this.limit = limit;
        }
    }

    private static final class Sent {
        final String vendor;
        final String batchId;
        final List<Entry> entries = new CopyOnWriteArrayList<>();
        Instant lastPoll;

        Sent(String vendor, String batchId) {
            this.vendor = vendor;
            this.batchId = batchId;
        }
    }

    private final BatchClient client;
    private final TaskGenCallRepository calls;
    private final TaskGenJobRepository jobs;
    private final TaskGenBatchProperties props;
    private final Clock clock;
    private final String env;
    private final List<Entry> queue = new CopyOnWriteArrayList<>();
    private final Map<String, Sent> sent = new LinkedHashMap<>(); // tick 스레드만 건드린다(submit 이 다시 확인할 배치는 reattached 로 넘긴다)
    private final List<Sent> reattached = new CopyOnWriteArrayList<>();
    private final AtomicBoolean ticking = new AtomicBoolean();

    @Autowired
    public BatchCallService(BatchClient client, TaskGenCallRepository calls, TaskGenJobRepository jobs, TaskGenBatchProperties props, Clock clock,
            @Value("${pair.env-name:local-${COMPUTERNAME:${HOSTNAME:dev}}}") String env) {
        this.client = client;
        this.calls = calls;
        this.jobs = jobs;
        this.props = props;
        this.clock = clock;
        this.env = env;
    }

    // ── 맡기기 ──

    /** 호출을 맡기고 결과(또는 바로 호출로 바꾸라는 답)가 올 때까지 기다린다 */
    public Reply submit(Request req) {
        Instant now = clock.instant();
        String vendor = BatchClient.vendorOf(req.model());
        Duration limit = req.review() ? props.waitReview() : props.waitGenerate();
        Entry e;
        TaskGenCall existing = find(req);
        if (existing != null && TaskGenCall.PENDING.equals(existing.getStatus()) && existing.getProviderBatchId() != null) {
            // 서버를 다시 켠 뒤: 이미 보낸 배치가 있으면 새로 보내지 않고 그 배치를 다시 확인한다
            e = new Entry(existing.getId(), req, vendor, now, limit);
            e.batchId = existing.getProviderBatchId();
            Sent s = new Sent(existing.getVendor() == null || existing.getVendor().isBlank() ? vendor : existing.getVendor(), e.batchId);
            s.entries.add(e);
            reattached.add(s);
        } else {
            String id = existing != null ? existing.getId() : UUID.randomUUID().toString();
            e = new Entry(id, req, vendor, now, limit);
            if (existing == null) record(TaskGenCall.pendingBatch(id, req.jobId(), req.hash(), req.nth(), req.stage(), req.label(), vendor, null, now));
            queue.add(e);
        }
        try {
            return e.future.get();
        } catch (InterruptedException ie) {
            e.finished = true;
            Thread.currentThread().interrupt();
            throw new IllegalStateException("배치 대기가 중단됐습니다.", ie);
        } catch (ExecutionException ee) {
            throw new IllegalStateException(ee.getCause());
        }
    }

    private TaskGenCall find(Request r) {
        try {
            return calls.findByJobIdAndInputHashAndNth(r.jobId(), r.hash(), r.nth()).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("배치 호출 기록을 찾지 못했습니다(새로 보냅니다): {}", ex.getMessage());
            return null;
        }
    }

    private void record(TaskGenCall c) {
        try {
            calls.save(c);
        } catch (RuntimeException ex) {
            log.warn("배치 호출 기록을 저장하지 못했습니다(배치는 계속): {}", ex.getMessage());
        }
    }

    // ── 마감 ──

    /**
     * 남은 시간이 (남은 단계 수 × 바로 호출 평균 + 여유)보다 적으면 true. 남은 단계 수는 단계 순서 목록에서 이 단계부터 끝까지로 어림한다.
     * 마감이 없는 작업은 false
     */
    boolean deadlineTight(Instant deadlineAt, TaskPipelineService.Stage stage) {
        if (deadlineAt == null) return false;
        int remaining = TaskPipelineService.Stage.values().length - stage.ordinal();
        Duration need = Duration.ofSeconds((long) remaining * props.directStepSeconds()).plus(props.margin());
        return Duration.between(clock.instant(), deadlineAt).compareTo(need) < 0;
    }

    static boolean isReview(TaskPipelineService.Stage stage) {
        return stage == TaskPipelineService.Stage.REVIEW || stage == TaskPipelineService.Stage.REVIEW2 || stage == TaskPipelineService.Stage.FILLER_REVIEW;
    }

    // ── 돌리기 ──

    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    public void scheduled() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("배치 창구 점검 실패(다음 주기에 다시): {}", e.getMessage());
        }
    }

    /** 대기 상한 넘긴 요청을 빼고, 보낼 때가 되면 보내고, poll 간격이 된 배치의 상태를 본다 */
    void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            Instant now = clock.instant();
            for (Sent s : reattached) {
                reattached.remove(s);
                Sent have = sent.get(s.batchId);
                if (have == null) sent.put(s.batchId, s);
                else have.entries.addAll(s.entries);
            }
            expire(now);
            if (shouldSend(now)) send(now);
            poll(now);
        } finally {
            ticking.set(false);
        }
    }

    private void expire(Instant now) {
        for (Entry e : queue) {
            if (e.finished || !now.isBefore(e.enqueuedAt.plus(e.limit))) {
                queue.remove(e);
                if (!e.finished) complete(e, null, WAIT, now);
            }
        }
        for (Iterator<Sent> it = sent.values().iterator(); it.hasNext();) {
            Sent s = it.next();
            for (Entry e : s.entries) {
                if (!e.finished && !now.isBefore(e.enqueuedAt.plus(e.limit))) complete(e, null, WAIT, now); // 늦게 온 결과는 버린다
            }
            if (s.entries.stream().allMatch(x -> x.finished)) { // 그 배치에 남은 요청이 모두 빠졌을 때만 배치를 취소한다
                try {
                    client.cancel(s.vendor, s.batchId);
                } catch (RuntimeException ex) {
                    log.warn("배치 취소 실패(그냥 둡니다): {}", ex.getMessage());
                }
                it.remove();
            }
        }
    }

    private boolean shouldSend(Instant now) {
        if (queue.isEmpty()) return false;
        Instant oldest = queue.stream().map(e -> e.enqueuedAt).min(Instant::compareTo).orElse(now);
        if (!now.isBefore(oldest.plus(props.hold()))) return true;
        Set<String> blocked = new HashSet<>();
        queue.forEach(e -> blocked.add(e.req.jobId()));
        sent.values().forEach(s -> s.entries.stream().filter(e -> !e.finished).forEach(e -> blocked.add(e.req.jobId())));
        long running;
        try {
            running = jobs.countByEnvAndStatusAndMode(env, TaskGenJob.RUNNING, TaskGenJob.BATCH);
        } catch (RuntimeException ex) {
            return false;
        }
        return blocked.size() >= running;
    }

    private void send(Instant now) {
        List<Entry> take = new ArrayList<>(queue);
        queue.removeAll(take);
        Map<String, List<Entry>> groups = new LinkedHashMap<>(); // 회사별(OpenAI 는 모델별) 한 배치
        for (Entry e : take) groups.computeIfAbsent(e.vendor + ("openai".equals(e.vendor) ? "|" + e.req.model() : ""), k -> new ArrayList<>()).add(e);
        for (List<Entry> g : groups.values()) {
            List<BatchClient.Req> reqs = new ArrayList<>();
            List<Entry> ok = new ArrayList<>();
            for (Entry e : g) {
                try {
                    Request r = e.req;
                    reqs.add(client.generateReq(e.id, r.model(), r.system(), r.user(), r.type(), r.maxTokens(), r.effort()));
                    ok.add(e);
                } catch (RuntimeException ex) {
                    log.warn("배치 요청 본문을 만들지 못해 바로 호출로 바꿉니다: {}", ex.getMessage());
                    complete(e, null, FAILED, now);
                }
            }
            if (ok.isEmpty()) continue;
            String vendor = ok.get(0).vendor;
            try {
                String id = client.create(vendor, reqs);
                Sent s = new Sent(vendor, id);
                s.entries.addAll(ok);
                s.lastPoll = now;
                ok.forEach(e -> e.batchId = id);
                sent.put(id, s);
                try {
                    calls.attachBatch(ok.stream().map(e -> e.id).toList(), id);
                } catch (RuntimeException ex) {
                    log.warn("배치 id 를 기록하지 못했습니다(배치는 계속): {}", ex.getMessage());
                }
            } catch (RuntimeException ex) {
                log.warn("배치를 보내지 못해 {}개 호출을 바로 호출로 바꿉니다: {}", ok.size(), ex.getMessage());
                ok.forEach(e -> complete(e, null, FAILED, now));
            }
        }
    }

    private void poll(Instant now) {
        for (Iterator<Sent> it = sent.values().iterator(); it.hasNext();) {
            Sent s = it.next();
            if (s.lastPoll != null && now.isBefore(s.lastPoll.plus(props.poll()))) continue;
            s.lastPoll = now;
            try {
                BatchClient.Status st = client.status(s.vendor, s.batchId);
                if (!st.done()) continue;
                Map<String, BatchClient.Answer> answers = client.answers(s.vendor, st);
                for (Entry e : s.entries) {
                    if (e.finished) continue; // 대기 초과로 이미 바로 호출로 바뀐 요청: 늦게 온 결과는 버린다
                    complete(e, answers.get(e.id), null, now);
                }
                it.remove();
            } catch (RuntimeException ex) {
                log.warn("배치 상태를 보지 못했습니다(다음에 다시): {}", ex.getMessage());
            }
        }
    }

    /** answer 가 있으면 읽어서 답으로, 없거나 못 읽으면 fallback 이유로 */
    private void complete(Entry e, BatchClient.Answer a, String reason, Instant now) {
        if (e.finished) return;
        e.finished = true;
        long waited = Duration.between(e.enqueuedAt, now).toMillis();
        if (reason != null) {
            e.future.complete(new Reply(null, 0, 0, 0, waited, reason));
            return;
        }
        if (a == null || !a.ok()) {
            e.future.complete(new Reply(null, 0, 0, 0, waited, FAILED));
            return;
        }
        String finish = a.finishReason() == null ? "" : a.finishReason();
        if (finish.contains("length") || finish.contains("max_tokens")) { // 출력 한도 끊김: 바로 호출로 다시(ModelClient 의 끊김 처리 규칙)
            e.future.complete(new Reply(null, a.inputTokens(), a.outputTokens(), a.cachedInputTokens(), waited, LENGTH));
            return;
        }
        try {
            if (a.text() == null || a.text().isBlank()) throw new IllegalStateException("빈 답");
            Object value = BatchClient.read(e.req.type(), a.text());
            e.future.complete(new Reply(value, a.inputTokens(), a.outputTokens(), a.cachedInputTokens(), waited, null));
        } catch (RuntimeException ex) {
            log.warn("배치 답을 읽지 못해 바로 호출로 바꿉니다: {}", ex.getMessage());
            e.future.complete(new Reply(null, a.inputTokens(), a.outputTokens(), a.cachedInputTokens(), waited, FAILED));
        }
    }

    // ── 보기(테스트·진단) ──

    int queuedCount() {
        return queue.size();
    }

    int reattachedCount() {
        return reattached.size();
    }

    int sentBatchCount() {
        return sent.size();
    }
}
