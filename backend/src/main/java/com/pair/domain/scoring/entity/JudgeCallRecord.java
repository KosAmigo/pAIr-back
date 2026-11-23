package com.pair.domain.scoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 채점 모델 호출 한 번 (ERD judge_calls) */
@Entity
@Table(name = "judge_calls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JudgeCallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "judge_call_id")
    private Long id;

    @Column(name = "scoring_id", nullable = false)
    private Long scoringId;

    @Column(nullable = false, length = 50)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cached_tokens", nullable = false)
    private int cachedTokens;

    public JudgeCallRecord(Long scoringId, String model, int inputTokens, int outputTokens, int cachedTokens) {
        this.scoringId = scoringId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedTokens = cachedTokens;
    }

}
