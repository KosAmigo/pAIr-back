package com.pair.domain.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문항 (ERD task_questions). TASK 문항(객관식·단답형)과 후속 질문(FOLLOWUP, ESSAY) */
@Entity
@Table(name = "task_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskQuestion {

    public static final String FOLLOWUP = "FOLLOWUP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** TASK | FOLLOWUP */
    @Column(nullable = false, length = 10)
    private String stage;

    /** SINGLE | MULTI | SHORT | ESSAY */
    @Column(name = "question_type", nullable = false, length = 10)
    private String questionType;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 배점 (TASK 문항만. 객관식 5, 단답형 3) */
    private Integer points;

    @Column(nullable = false)
    private boolean active = true;

    /** 테스트·시드용 */
    public TaskQuestion(Long taskId, String stage, String questionType, int seq, String content) {
        this.taskId = taskId;
        this.stage = stage;
        this.questionType = questionType;
        this.seq = seq;
        this.content = content;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }
}
