package com.pair.domain.question.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 단답형 답변 (ERD text_answers). 응시·문항마다 한 행 */
@Entity
@Table(name = "text_answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TextAnswerRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "text_answer_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "question_type", nullable = false, length = 10)
    private String questionType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    public TextAnswerRecord(Long attemptId, Long questionId, String questionType, String content, Instant answeredAt) {
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.questionType = questionType;
        this.content = content;
        this.answeredAt = answeredAt;
    }

    public void update(String content, Instant at) {
        this.content = content;
        this.answeredAt = at;
    }
}
