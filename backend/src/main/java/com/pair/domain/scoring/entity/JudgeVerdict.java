package com.pair.domain.scoring.entity;

import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 채점 모델 호출별 판정 (ERD judge_verdicts + verdict_evidences) */
@Entity
@Table(name = "judge_verdicts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JudgeVerdict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verdict_id")
    private Long id;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    @Column(name = "judge_call_id", nullable = false)
    private Long judgeCallId;

    @Column(nullable = false)
    private boolean violated;

    @Column(name = "count", nullable = false)
    private int count;

    @Column(columnDefinition = "text")
    private String reason;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "verdict_evidences", joinColumns = @JoinColumn(name = "verdict_id"))
    @Column(name = "ref", nullable = false, length = 50)
    private Set<String> evidences = new LinkedHashSet<>();

    public JudgeVerdict(Long resultId, Long judgeCallId, boolean violated, int count, String reason, Set<String> evidences) {
        this.resultId = resultId;
        this.judgeCallId = judgeCallId;
        this.violated = violated;
        this.count = count;
        this.reason = reason;
        this.evidences = new LinkedHashSet<>(evidences);
    }

}
