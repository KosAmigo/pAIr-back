package com.pair.domain.attempt.entity;

import java.time.Instant;

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

/** 체크포인트 (ERD checkpoints): 질문 턴 하나를 보낸 순간의 파일 상태. 질문마다 자동으로 만든다 */
@Entity
@Table(name = "checkpoints", uniqueConstraints = @UniqueConstraint(columnNames = {"attempt_id", "turn_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checkpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkpoint_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    /** 항상 USER (복합 외래 키용) */
    @Column(name = "turn_role", nullable = false, length = 10)
    private String turnRole = "USER";

    @Column(length = 100)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 이름을 붙이거나(최대 100자) 지운다(null) */
    public void rename(String label) {
        this.label = label;
    }

    public Checkpoint(Long attemptId, int turnNo, Instant createdAt) {
        this.attemptId = attemptId;
        this.turnNo = turnNo;
        this.createdAt = createdAt;
    }
}
