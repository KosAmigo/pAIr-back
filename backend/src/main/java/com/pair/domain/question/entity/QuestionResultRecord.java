package com.pair.domain.question.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문항 채점 결과 (ERD question_results). 채점(scoring_id)마다 문항 하나가 한 행 */
@Entity
@Table(name = "question_results")
@IdClass(QuestionResultRecord.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionResultRecord {

    public record Key(Long scoringId, Long questionId) implements Serializable {}

    public static final String AUTO = "AUTO";

    @Id
    @Column(name = "scoring_id")
    private Long scoringId;

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(columnDefinition = "text")
    private String reason;

    public QuestionResultRecord(Long scoringId, Long questionId, int score, String method, String reason) {
        this.scoringId = scoringId;
        this.questionId = questionId;
        this.score = score;
        this.method = method;
        this.reason = reason;
    }
}
