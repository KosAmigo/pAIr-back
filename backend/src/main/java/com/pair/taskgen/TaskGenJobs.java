package com.pair.taskgen;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 과제 생성 작업을 비동기로 돌리고 상태를 메모리에 둔다(서버를 다시 켜면 사라져도 된다).
 * 동시에 도는 작업은 pair.taskgen.max-concurrent 개까지, 넘으면 TooManyJobsException(429).
 */
@Component
public class TaskGenJobs {

    private static final Logger log = LoggerFactory.getLogger(TaskGenJobs.class);
    private static final Duration KEEP_FINISHED = Duration.ofHours(6);

    public enum State { RUNNING, DONE, FAILED }

    /** GET /api/admin/task-gen/{jobId} 응답. DONE 이어도 passed 가 false 면 확인에 실패한 채 검수 전으로 저장된 것 */
    public record JobStatus(String jobId, State status, String step, String taskCode, Boolean passed,
            List<CheckResult> checks, String error) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Instant createdAt = Instant.now();
        volatile Instant finishedAt;
        volatile State state = State.RUNNING;
        volatile String step = "대기 중";
        volatile String taskCode;
        volatile Boolean passed;
        volatile List<CheckResult> checks = List.of();
        volatile String error;

        JobStatus view() {
            return new JobStatus(id, state, step, taskCode, passed, checks, error);
        }
    }

    private final DevTaskGenerator dev;
    private final BizTaskGenerator biz;
    private final int maxConcurrent;
    private final TaskFileStorageGuard storageGuard;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TaskGenJobs(DevTaskGenerator dev, BizTaskGenerator biz, TaskGenProperties props, TaskFileStorageGuard storageGuard) {
        this.storageGuard = storageGuard;
        this.dev = dev;
        this.biz = biz;
        this.maxConcurrent = props.maxConcurrent();
    }

    /** 폼은 검증이 끝난 것이어야 한다. 작업을 시작하고 jobId 를 돌려준다 */
    public String start(TaskGenForm form, long adminId) {
        storageGuard.requireSafe(form.track()); // AI 를 부르기 전에 막는다
        Job job = new Job();
        synchronized (this) {
            evictOld();
            long running = jobs.values().stream().filter(j -> j.state == State.RUNNING).count();
            if (running >= maxConcurrent) {
                throw new TooManyJobsException("동시에 만들 수 있는 과제는 " + maxConcurrent + "개입니다. 하나가 끝난 뒤 다시 시도하세요.");
            }
            jobs.put(job.id, job);
        }
        executor.submit(() -> run(job, form, adminId));
        return job.id;
    }

    public JobStatus status(String jobId) {
        Job j = jobs.get(jobId);
        if (j == null) throw new NoSuchElementException("없는 작업입니다: " + jobId);
        return j.view();
    }

    private void run(Job job, TaskGenForm form, long adminId) {
        try {
            TaskGenerator<?> generator = "BIZ".equals(form.track()) ? biz : dev;
            TaskGenerator.Outcome o = generator.generate(form, adminId, step -> job.step = step);
            job.taskCode = o.taskCode();
            job.passed = o.passed();
            job.checks = o.checks();
            job.step = "완료";
            job.state = State.DONE;
        } catch (Exception e) {
            log.warn("과제 생성 실패: {}", e.getMessage());
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = State.FAILED;
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    private void evictOld() {
        Instant limit = Instant.now().minus(KEEP_FINISHED);
        jobs.values().removeIf(j -> j.finishedAt != null && j.finishedAt.isBefore(limit));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
