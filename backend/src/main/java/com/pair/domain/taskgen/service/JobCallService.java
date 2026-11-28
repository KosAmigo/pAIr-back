package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 생성 작업 하나가 부른 모델 호출을 저장하고 다시 꺼내 쓴다(이어 가기, 설계 7장). 작업마다 하나 만든다.
 * 같은 (입력 해시, nth)에 저장된 결과가 있으면 모델을 다시 부르지 않는다. nth 는 이 작업 안에서 해시별로 센 호출 순번이라,
 * 관문 검사처럼 동시에 도는 호출의 순서가 매번 달라도 같은 입력은 같은 자리를 찾는다.
 * 작업을 돌리는 스레드에 bind 해 두면 StageCallService 가 만들어질 때 이 객체를 집어 간다.
 */
public final class JobCallService {

    private static final Logger log = LoggerFactory.getLogger(JobCallService.class);
    private static final ThreadLocal<JobCallService> CURRENT = new ThreadLocal<>();

    private final String jobId;
    private final TaskGenCallRepository repo;
    private final ObjectMapper json;
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final AtomicInteger reused = new AtomicInteger();
    private final DoubleAdder cost = new DoubleAdder();
    private final BatchCallService window; // 배치(BATCH) 작업이면 모델 호출을 맡길 창구, 아니면 null
    private final Instant deadlineAt;
    private volatile String directForRest; // 마감이 모자라 이후 호출을 모두 바로 호출로 돌리는 중이면 그 이유(deadline)

    public JobCallService(String jobId, TaskGenCallRepository repo, ObjectMapper json) {
        this(jobId, repo, json, null, null);
    }

    /** window 가 있으면 이 작업의 모델 호출을 배치 창구에 맡긴다. deadlineAt 은 배치 작업의 마감 시각 */
    public JobCallService(String jobId, TaskGenCallRepository repo, ObjectMapper json, BatchCallService window, Instant deadlineAt) {
        this.jobId = jobId;
        this.repo = repo;
        this.json = json;
        this.window = window;
        this.deadlineAt = deadlineAt;
    }

    String jobId() {
        return jobId;
    }

    /** 배치 창구(배치 작업이 아니면 null) */
    BatchCallService window() {
        return window;
    }

    Instant deadlineAt() {
        return deadlineAt;
    }

    /** 마감 부족으로 이후 호출을 모두 바로 호출로 돌리기로 했는가 */
    boolean directForRest() {
        return directForRest != null;
    }

    void goDirectForRest(String reason) {
        if (directForRest == null) directForRest = reason;
    }

    /** 지금 스레드에 묶인 작업의 호출 저장소(없으면 null: 저장·재사용을 하지 않는다) */
    static JobCallService current() {
        return CURRENT.get();
    }

    /** 이 스레드에서 도는 동안 호출을 이 작업에 저장한다. 끝나면 반드시 unbind */
    public void bind() {
        CURRENT.set(this);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /** 입력 해시: 모델 이름, 추론 강도, 출력 한도, system, user, 결과 타입 이름의 sha-256 */
    static String hash(String model, String effort, int maxTokens, String system, String user, String typeName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {model, effort, String.valueOf(maxTokens), system, user, typeName}) {
                byte[] b = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                md.update(String.valueOf(b.length).getBytes(StandardCharsets.UTF_8)); // 이어 붙여도 경계가 섞이지 않게 길이를 먼저 넣는다
                md.update((byte) ':');
                md.update(b);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 해시의 다음 호출 순번(0부터) */
    int nextNth(String hash) {
        return counters.computeIfAbsent(hash, k -> new AtomicInteger()).getAndIncrement();
    }

    /** 저장된(DONE) 호출. 없거나 읽을 수 없으면 empty */
    Optional<TaskGenCall> find(String hash, int nth) {
        try {
            return repo.findByJobIdAndInputHashAndNth(jobId, hash, nth).filter(c -> TaskGenCall.DONE.equals(c.getStatus()) && c.getResult() != null);
        } catch (RuntimeException e) {
            log.warn("저장된 호출을 찾지 못했습니다(그냥 부릅니다): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 저장된 결과를 type 으로 읽는다. 읽을 수 없으면(타입이 바뀜 등) empty */
    <T> Optional<T> read(TaskGenCall call, Class<T> type) {
        try {
            return Optional.ofNullable(json.readValue(call.getResult(), type));
        } catch (Exception e) {
            log.warn("저장된 호출 결과를 읽지 못했습니다(다시 부릅니다): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 부른 결과를 저장한다. 저장이 실패해도 생성은 계속한다(다음에 이어 가지 못할 뿐) */
    void save(String hash, int nth, String stage, String label, String model, Object value, long inputTokens, long outputTokens) {
        save(hash, nth, stage, label, model, value, inputTokens, outputTokens, StageUsageDto.DIRECT, 0);
    }

    /**
     * via 가 BATCH 면 배치로 받은 결과. 같은 자리(작업, 해시, nth)에 배치 대기(PENDING) 행이 있으면 그 행을 DONE 으로 바꾼다
     * (대기 초과로 배치에서 빼고 바로 부른 결과도 같은 행에 들어간다)
     */
    void save(String hash, int nth, String stage, String label, String model, Object value, long inputTokens, long outputTokens, String via, long waitedMs) {
        try {
            String vendor = vendorOf(model);
            String result = json.writeValueAsString(value);
            var existing = repo.findByJobIdAndInputHashAndNth(jobId, hash, nth);
            if (existing.isPresent()) {
                TaskGenCall c = existing.get();
                c.done(via, vendor, result, inputTokens, outputTokens, 0, waitedMs);
                repo.save(c);
                return;
            }
            TaskGenCall c = new TaskGenCall(UUID.randomUUID().toString(), jobId, hash, nth, stage, label, vendor, result, inputTokens, outputTokens, Instant.now());
            if (!StageUsageDto.DIRECT.equals(via) || waitedMs > 0) c.done(via, vendor, result, inputTokens, outputTokens, 0, waitedMs);
            repo.save(c);
        } catch (Exception e) {
            log.warn("호출 결과를 저장하지 못했습니다(생성은 계속): {}", e.getMessage());
        }
    }

    static String vendorOf(String model) {
        return model == null ? "" : model.startsWith("claude") ? "anthropic" : model.startsWith("gpt") ? "openai" : "";
    }

    void markReused() {
        reused.incrementAndGet();
    }

    void addCost(double usd) {
        cost.add(usd);
    }

    /** 저장된 결과를 다시 쓴 횟수(report 에 '저장된 결과 재사용 n번') */
    public int reusedCount() {
        return reused.get();
    }

    /** 이 작업이 StageCallService 로 부른 호출의 비용($). 재사용한 호출도 한 번만 센다 */
    public double costUsd() {
        return cost.sum();
    }
}
