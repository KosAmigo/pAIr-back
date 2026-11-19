package com.pair.task;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 과제 행 (ERD tasks). 목록·공개 상태·용도(SELF/LIVE)는 DB 가 맡고, 과제 내용(설명·파일·숨은 테스트)은 아직
 * classpath:tasks/{원본 code} 에 있어 TaskRepository 가 읽는다. 실전(LIVE) 과제는 자가진단 원본의 복사본이라
 * parent_task_id 로 원본을 가리킨다. 행은 Flyway(V3)로 넣고, 여기서는 공개 상태만 바꾼다.
 */
@Entity
@Table(name = "tasks")
public class TaskRecord {

    public enum Usage { SELF, LIVE }

    /** OPEN 공개·응시 가능 / CLOSED 목록에만 보이고 응시 막힘 / HIDDEN 숨김(검수 대기) */
    public enum Status { OPEN, CLOSED, HIDDEN }

    @Id
    @Column(name = "task_id")
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    /** DEV | BIZ */
    @Column(nullable = false, length = 10)
    private String track;

    @Column(name = "time_limit_min", nullable = false)
    private int timeLimitMin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Usage usage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected TaskRecord() {}

    /** 테스트·시드용 */
    public TaskRecord(Long id, String code, String title, String track, int timeLimitMin, Usage usage, Status status,
            Long parentTaskId, Long createdBy, Instant at) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.track = track;
        this.timeLimitMin = timeLimitMin;
        this.usage = usage;
        this.status = status;
        this.parentTaskId = parentTaskId;
        this.createdBy = createdBy;
        this.updatedAt = at;
        this.publishedAt = status == Status.HIDDEN ? null : at;
    }

    /** 처음 공개한 시각은 다시 숨겨도 남긴다 (ERD: HIDDEN 이 아니면 published_at 필수) */
    public void changeStatus(Status next, Instant at) {
        this.status = next;
        this.updatedAt = at;
        if (next != Status.HIDDEN && publishedAt == null) publishedAt = at;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
    public String getTrack() { return track; }
    public int getTimeLimitMin() { return timeLimitMin; }
    public Usage getUsage() { return usage; }
    public Status getStatus() { return status; }
    public Long getParentTaskId() { return parentTaskId; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
