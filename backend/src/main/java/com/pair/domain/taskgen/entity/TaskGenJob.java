package com.pair.domain.taskgen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** AI 과제 생성 작업 한 건(task_gen_jobs). 상태 바꾸기는 repository 의 조건부 UPDATE 로만 한다(여러 서버가 같이 줍기 때문) */
@Entity
@Table(name = "task_gen_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskGenJob {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    public static final String NOW = "NOW";
    public static final String BATCH = "BATCH";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(nullable = false, length = 100)
    private String env;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(length = 100)
    private String step;

    @Column(nullable = false, length = 10)
    private String mode;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(nullable = false, columnDefinition = "text")
    private String form;

    @Column(name = "admin_id", nullable = false)
    private long adminId;

    @Column(name = "task_code", length = 50)
    private String taskCode;

    private Boolean passed;

    @Column(columnDefinition = "text")
    private String checks;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "cost_usd")
    private Double costUsd;

    @Column(name = "worker_id", length = 36)
    private String workerId;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 새 대기 작업 */
    public TaskGenJob(String id, String batchId, String env, String mode, Instant deadlineAt, String form, long adminId, Instant now) {
        this.id = id;
        this.batchId = batchId;
        this.env = env;
        this.status = QUEUED;
        this.step = "대기 중";
        this.mode = mode;
        this.deadlineAt = deadlineAt;
        this.form = form;
        this.adminId = adminId;
        this.createdAt = now;
    }
}
