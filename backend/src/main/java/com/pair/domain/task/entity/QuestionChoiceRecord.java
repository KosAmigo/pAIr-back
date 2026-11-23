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

/** 객관식 보기 (ERD question_choices) */
@Entity
@Table(name = "question_choices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionChoiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "choice_id")
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    public QuestionChoiceRecord(Long questionId, int seq, String content, boolean correct) {
        this.questionId = questionId;
        this.seq = seq;
        this.content = content;
        this.correct = correct;
    }
}
