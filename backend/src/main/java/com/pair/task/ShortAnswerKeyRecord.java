package com.pair.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 단답형 인정 답안 (ERD short_answer_keys) */
@Entity
@Table(name = "short_answer_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortAnswerKeyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_key_id")
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false, length = 200)
    private String accepted;

    public ShortAnswerKeyRecord(Long questionId, String accepted) {
        this.questionId = questionId;
        this.accepted = accepted;
    }
}
