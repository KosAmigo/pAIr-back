package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.entity.TaskGenJob;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.config.TaskGenProperties;
import com.pair.global.error.ConflictException;
import com.pair.infra.storage.TaskFileStorageGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 과제 생성 작업의 대기열과 실행(설계 7장). 작업 상태는 DB(task_gen_jobs)에 있어 서버를 다시 켜도 이어 간다.
 * - 시작 요청은 QUEUED 로 저장만 하고 바로 돌려준다(상한을 넘어도 거절하지 않고 대기).
 * - 워커(5초마다)가 내 env 의 QUEUED 를 오래된 순으로 (상한 - env 전체 RUNNING 수)개 조건부 UPDATE 로 집어 가상 스레드에서 돌린다.
 * - 돌리는 서버는 30초마다 heartbeat 를 갱신하고, 2분 넘게 끊긴 RUNNING 작업은 QUEUED 로 되돌려 다시 줍는다.
 *   다시 돌 때는 StageCallService 가 저장된 모델 호출 결과를 써서 비용 없이 끊긴 지점까지 간다(JobCallService).
 * 상한은 줍는 순간 세는 값이라 서버 여러 대가 동시에 줍는 순간에는 잠깐 넘을 수 있다.
 */
@Component
public class TaskGenJobService {

    private static final Logger log = LoggerFactory.getLogger(TaskGenJobService.class);
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    static final Duration RECENT_FINISHED = Duration.ofHours(24);
    private static final Set<String> ACTIVE = Set.of(TaskGenJob.QUEUED, TaskGenJob.RUNNING);
    private static final Set<Integer> DEADLINE_HOURS = Set.of(1, 3, 6);

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

    /**
     * 작업 응답(GET /api/admin/task-gen/{jobId}, jobs 목록의 한 줄). DONE 이어도 passed 가 false 면 확인에 실패한 채 검수 전으로 저장된 것.
     * queuePosition 은 QUEUED 일 때만(내 env 의 대기 순번, 1부터). topic·track·minutes 는 폼에서 꺼낸 값
     */
    public record JobStatus(String jobId, State status, String step, String taskCode, Boolean passed,
            List<GenCheckResultDto> checks, String error, String mode, Instant deadlineAt, Integer queuePosition,
            String batchId, String topic, String track, Integer minutes, Double costUsd, Instant createdAt) {}

    /** 자동 생성으로 한 번에 넣은 작업들 */
    public record Batch(String batchId, List<String> jobIds) {}

    private final DevTaskGeneratorService dev;
    private final BizTaskGeneratorService biz;
    private final TaskFileStorageGuard storageGuard;
    private final TaskGenJobRepository jobs;
    private final TaskGenCallRepository calls;
    private final ObjectMapper json;
    private final String env;
    private final int maxConcurrent;
    private final boolean workerEnabled;
    private final String workerId = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;

    @Autowired
    public TaskGenJobService(DevTaskGeneratorService dev, BizTaskGeneratorService biz, TaskGenProperties props, TaskFileStorageGuard storageGuard,
            TaskGenJobRepository jobs, TaskGenCallRepository calls, ObjectMapper json,
            @Value("${pair.env-name:local-${COMPUTERNAME:${HOSTNAME:dev}}}") String env,
            @Value("${pair.taskgen.worker.enabled:true}") boolean workerEnabled) {
        this(dev, biz, storageGuard, jobs, calls, json, env, props.maxConcurrent(), workerEnabled);
    }

    /** 환경 이름·동시 상한·워커 스위치를 직접 정한다(테스트가 새 Spring 컨텍스트 없이 쓴다) */
    public TaskGenJobService(DevTaskGeneratorService dev, BizTaskGeneratorService biz, TaskFileStorageGuard storageGuard,
            TaskGenJobRepository jobs, TaskGenCallRepository calls, ObjectMapper json, String env, int maxConcurrent, boolean workerEnabled) {
        this.dev = dev;
        this.biz = biz;
        this.storageGuard = storageGuard;
        this.jobs = jobs;
        this.calls = calls;
        this.json = json;
        this.env = env;
        this.maxConcurrent = maxConcurrent;
        this.workerEnabled = workerEnabled;
    }

    // ── 시작·조회·취소 ──

    /** 폼은 검증이 끝난 것이어야 한다. 바로 생성(NOW) 작업을 대기열에 넣고 jobId 를 돌려준다 */
    public String start(TaskGenRequest form, long adminId) {
        return start(form, adminId, TaskGenJob.NOW, null, null);
    }

    /** mode 는 NOW/BATCH(지금은 BATCH 도 NOW 처럼 돈다). BATCH 면 deadlineHours(1/3/6, 기본 3)로 마감 시각을 정한다 */
    public String start(TaskGenRequest form, long adminId, String mode, Integer deadlineHours, String batchId) {
        storageGuard.requireSafe(form.track()); // AI 를 부르기 전에 막는다
        String m = checkedMode(mode);
        Instant now = Instant.now();
        Instant deadline = null;
        if (TaskGenJob.BATCH.equals(m)) {
            int h = deadlineHours == null ? 3 : deadlineHours;
            if (!DEADLINE_HOURS.contains(h)) throw new IllegalArgumentException("batchDeadlineHours 는 1, 3, 6 중 하나여야 합니다.");
            deadline = now.plus(Duration.ofHours(h));
        }
        String id = UUID.randomUUID().toString();
        jobs.save(new TaskGenJob(id, batchId, env, m, deadline, toJson(form), adminId, now));
        return id;
    }

    /** 폼 여러 개를 한 묶음(batchId)으로 대기열에 넣는다 */
    public Batch startAll(List<TaskGenRequest> forms, long adminId, String mode, Integer deadlineHours) {
        checkedMode(mode);
        forms.forEach(f -> storageGuard.requireSafe(f.track())); // 하나도 넣기 전에 막는다
        String batchId = UUID.randomUUID().toString();
        List<String> ids = new ArrayList<>();
        for (TaskGenRequest f : forms) ids.add(start(f, adminId, mode, deadlineHours, batchId));
        return new Batch(batchId, ids);
    }

    public JobStatus status(String jobId) {
        return view(jobs.findById(jobId).orElseThrow(() -> new NoSuchElementException("없는 작업입니다: " + jobId)));
    }

    /** 내 env 의 대기·실행 중 작업과 최근 24시간 안에 끝난 작업 */
    public List<JobStatus> active() {
        return jobs.findActive(env, ACTIVE, Instant.now().minus(RECENT_FINISHED)).stream().map(this::view).toList();
    }

    public List<JobStatus> batch(String batchId) {
        return jobs.findByBatchIdOrderByCreatedAtAsc(batchId).stream().map(this::view).toList();
    }

    /** 대기(QUEUED) 작업만 취소한다. 이미 시작했거나 끝났으면 ConflictException(409), 없으면 NoSuchElementException(404) */
    public JobStatus cancel(String jobId) {
        TaskGenJob j = jobs.findById(jobId).orElseThrow(() -> new NoSuchElementException("없는 작업입니다: " + jobId));
        if (jobs.cancel(jobId, Instant.now()) != 1) {
            throw new ConflictException("대기 중인 작업만 취소할 수 있습니다(지금 상태: " + j.getStatus() + ").");
        }
        return status(jobId);
    }

    private static String checkedMode(String mode) {
        String m = mode == null || mode.isBlank() ? TaskGenJob.NOW : mode.strip().toUpperCase();
        if (!m.equals(TaskGenJob.NOW) && !m.equals(TaskGenJob.BATCH)) throw new IllegalArgumentException("mode 는 NOW 또는 BATCH 여야 합니다.");
        return m;
    }

    // ── 워커 ──

    /** 5초마다: 끊긴 작업을 되돌리고, 자리가 있으면 내 env 의 대기 작업을 집어 돌린다. 워커가 꺼져 있으면 아무것도 안 한다 */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void tick() {
        if (!workerEnabled || stopping) return;
        try {
            int requeued = jobs.requeueStale(env, Instant.now().minus(STALE_AFTER));
            if (requeued > 0) log.warn("heartbeat 가 끊긴 생성 작업 {}개를 대기로 되돌렸습니다(env={})", requeued, env);
            long free = maxConcurrent - jobs.countByEnvAndStatus(env, TaskGenJob.RUNNING); // 상한은 env 전체의 RUNNING 으로 센다
            if (free <= 0) return;
            for (TaskGenJob j : jobs.findByEnvAndStatusOrderByCreatedAtAsc(env, TaskGenJob.QUEUED, PageRequest.of(0, (int) free))) {
                if (jobs.claim(j.getId(), workerId, Instant.now()) == 1) { // 1행일 때만 내 것
                    String id = j.getId();
                    executor.submit(() -> run(id));
                }
            }
        } catch (RuntimeException e) {
            log.warn("생성 작업 줍기 실패(다음 주기에 다시): {}", e.getMessage());
        }
    }

    /** 30초마다: 내가 돌리는 작업의 heartbeat 갱신 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeat() {
        if (!workerEnabled || stopping) return;
        try {
            jobs.heartbeat(workerId, Instant.now());
        } catch (RuntimeException e) {
            log.warn("heartbeat 갱신 실패: {}", e.getMessage());
        }
    }

    private void run(String jobId) {
        JobCallService recorder = new JobCallService(jobId, calls, json);
        recorder.bind();
        try {
            TaskGenJob job = jobs.findById(jobId).orElseThrow();
            TaskGenRequest form = json.readValue(job.getForm(), TaskGenRequest.class);
            TaskGeneratorService<?> generator = "BIZ".equals(form.track()) ? biz : dev;
            TaskGeneratorService.Outcome o = generator.generate(form, job.getAdminId(), step -> step(jobId, step));
            jobs.finish(jobId, workerId, TaskGenJob.DONE, "완료", o.taskCode(), o.passed(), toJson(o.checks()), null, recorder.costUsd(), Instant.now());
        } catch (Exception e) {
            if (stopping) return; // 서버가 꺼지는 중이라 끊긴 것: RUNNING 으로 두면 다음 서버가 heartbeat 규칙으로 이어 간다
            log.warn("과제 생성 실패: {}", e.getMessage());
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jobs.finish(jobId, workerId, TaskGenJob.FAILED, null, null, null, null, error, recorder.costUsd(), Instant.now());
        } finally {
            JobCallService.unbind();
        }
    }

    private void step(String jobId, String step) {
        try {
            jobs.updateStep(jobId, workerId, step);
        } catch (RuntimeException e) {
            log.warn("단계 기록 실패: {}", e.getMessage());
        }
    }

    // ── 응답 ──

    private JobStatus view(TaskGenJob j) {
        Integer position = TaskGenJob.QUEUED.equals(j.getStatus())
                ? (int) jobs.countByEnvAndStatusAndCreatedAtBefore(j.getEnv(), TaskGenJob.QUEUED, j.getCreatedAt()) + 1 : null;
        TaskGenRequest form = readForm(j.getForm());
        return new JobStatus(j.getId(), State.valueOf(j.getStatus()), j.getStep(), j.getTaskCode(), j.getPassed(), readChecks(j.getChecks()), j.getError(),
                j.getMode(), j.getDeadlineAt(), position, j.getBatchId(), form == null ? null : form.topic(), form == null ? null : form.track(),
                form == null ? null : form.minutes(), j.getCostUsd(), j.getCreatedAt());
    }

    private TaskGenRequest readForm(String text) {
        try {
            return json.readValue(text, TaskGenRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<GenCheckResultDto> readChecks(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            return json.readValue(text, new TypeReference<List<GenCheckResultDto>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 서버가 끊길 때: 돌던 작업을 실패로 두지 않는다(다음에 이어 간다) */
    @PreDestroy
    void shutdown() {
        stopping = true;
        executor.shutdownNow();
    }
}
