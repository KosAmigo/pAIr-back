package com.pair.scoring.db;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 채점 프롬프트 버전 (ERD prompt_versions). name 에는 파일 버전 id 를 넣어 찾는다. */
@Entity
@Table(name = "prompt_versions")
public class PromptVersionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String note;

    @Column(name = "parent_version_id")
    private Long parentVersionId;

    @Column(name = "judge_system", nullable = false, columnDefinition = "text")
    private String judgeSystem;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PromptVersionRecord() {}

    public PromptVersionRecord(String name, String note, Long parentVersionId, String judgeSystem, boolean active, Instant createdAt) {
        this.name = name;
        this.note = note;
        this.parentVersionId = parentVersionId;
        this.judgeSystem = judgeSystem;
        this.active = active;
        this.createdAt = createdAt;
    }

    public void setActive(boolean active) { this.active = active; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNote() { return note; }
    public Long getParentVersionId() { return parentVersionId; }
    public String getJudgeSystem() { return judgeSystem; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
