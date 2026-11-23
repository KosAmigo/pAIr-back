package com.pair.domain.scoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 체크 결과 (ERD check_results). status: DEDUCTED/PASSED/SPLIT/NO_EVIDENCE/SKIPPED */
@Entity
@Table(name = "check_results", uniqueConstraints = @UniqueConstraint(columnNames = {"scoring_id", "check_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long id;

    @Column(name = "scoring_id", nullable = false)
    private Long scoringId;

    @Column(name = "check_id", nullable = false)
    private Long checkId;

    @Column(nullable = false)
    private int deducted;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String reason;

    public CheckResult(Long scoringId, Long checkId, int deducted, String status, String reason) {
        this.scoringId = scoringId;
        this.checkId = checkId;
        this.deducted = deducted;
        this.status = status;
        this.reason = reason;
    }

}
