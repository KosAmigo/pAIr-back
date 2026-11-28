package com.pair.domain.taskissue.entity;

import java.time.Instant;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.task.entity.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "task_issue_reports")
public class TaskIssue {
    public enum Category { QUESTION_ERROR, ANSWER_EXPLANATION, DESCRIPTION_GAP, DIFFICULTY, OTHER }
    public enum Status { NEW, IN_REVIEW, RESOLVED, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "issue_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AuthAccount submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Category category;

    @Column(name = "question_number", length = 50)
    private String questionNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskIssue() {}

    public TaskIssue(Task task, AuthAccount submittedBy, Category category, String questionNumber, String message, Instant now) {
        this.task = task;
        this.submittedBy = submittedBy;
        this.category = category;
        this.questionNumber = questionNumber;
        this.message = message;
        this.status = Status.NEW;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeStatus(Status status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Task getTask() { return task; }
    public AuthAccount getSubmittedBy() { return submittedBy; }
    public Category getCategory() { return category; }
    public String getQuestionNumber() { return questionNumber; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
