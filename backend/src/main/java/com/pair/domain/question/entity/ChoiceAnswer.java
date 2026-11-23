package com.pair.domain.question.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 객관식 답변: 고른 보기 하나가 한 행 (ERD choice_answers) */
@Entity
@Table(name = "choice_answers")
@IdClass(ChoiceAnswer.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChoiceAnswer {

    public record Key(Long attemptId, Long choiceId) implements Serializable {}

    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @Id
    @Column(name = "choice_id")
    private Long choiceId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "question_type", nullable = false, length = 10)
    private String questionType;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    public ChoiceAnswer(Long attemptId, Long choiceId, Long questionId, String questionType, Instant answeredAt) {
        this.attemptId = attemptId;
        this.choiceId = choiceId;
        this.questionId = questionId;
        this.questionType = questionType;
        this.answeredAt = answeredAt;
    }
}
