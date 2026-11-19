package com.pair.taskconsole;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 과제 공개 상태. 행이 없으면 HIDDEN. 회차에는 OPEN 과제만 걸 수 있다 */
@Entity
@Table(name = "task_publications")
public class TaskPublication {

    public enum Status { HIDDEN, OPEN }

    @Id
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected TaskPublication() {}

    public TaskPublication(String taskId) {
        this.taskId = taskId;
        this.status = Status.HIDDEN;
    }

    public void change(Status next, Long by, Instant at) {
        this.status = next;
        this.updatedBy = by;
        this.updatedAt = at;
        if (next == Status.OPEN && publishedAt == null) publishedAt = at;
    }

    public String getTaskId() { return taskId; }
    public Status getStatus() { return status; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
