package com.pair.task;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 과제 행 (ERD tasks). 목록·공개 상태·용도(SELF/LIVE)와 과제 내용(설명·요구사항·settings)은 DB 가 맡고,
 * 파일·후속 질문은 task_files / task_questions 에서 TaskRepository 가 읽는다. 실전(LIVE) 과제는 자가진단 원본의
 * 복사본이라 parent_task_id 로 원본을 가리키며 내용 행을 따로 두지 않는다. 행은 Flyway(V3, V6)가 넣고, 여기서는 공개 상태만 바꾼다.
 */
@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    /** 과제 목록 카드의 한 줄 소개 */
    @Column(nullable = false, length = 200)
    private String summary = "";

    /** 과제 형태 (예: 장애 분석, 기능 구현) */
    @Column(nullable = false, length = 30)
    private String category = "";

    /** 초급 | 중급 | 고급 */
    @Column(nullable = false, length = 10)
    private String difficulty = "";

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String requirements;

    /** 응시 화면 설정 JSON: openFile, editableFile, requiredHeadings, d4Minutes. 내용이 들어 있는 원본 과제만 값이 있다 */
    @JdbcTypeCode(SqlTypes.JSON)
    private String settings;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

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
        this.createdAt = at;
        this.updatedAt = at;
        this.publishedAt = status == Status.HIDDEN ? null : at;
    }

    /** 목록 카드 정보 (AI 생성 과제 저장용) */
    public void setCard(String summary, String category, String difficulty) {
        this.summary = summary;
        this.category = category;
        this.difficulty = difficulty;
    }

    /** 테스트·시드용: 과제 내용 채우기 */
    public void setContent(String description, String requirements, String settingsJson) {
        this.description = description;
        this.requirements = requirements;
        this.settings = settingsJson;
    }

    /** 용도 바꾸기. 공개 전(HIDDEN) 원본 과제만 허용하는 검사는 서비스가 한다 */
    public void changeUsage(Usage next, Instant at) {
        this.usage = next;
        this.updatedAt = at;
    }

    /** 처음 공개한 시각은 다시 숨겨도 남긴다 (ERD: HIDDEN 이 아니면 published_at 필수) */
    public void changeStatus(Status next, Instant at) {
        this.status = next;
        this.updatedAt = at;
        if (next != Status.HIDDEN && publishedAt == null) publishedAt = at;
    }

}
