package com.pair.domain.attempt.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** AI 대화 한 턴 (ERD chat_turns). role 은 USER / AI */
@Entity
@Table(name = "chat_turns", uniqueConstraints = @UniqueConstraint(columnNames = {"attempt_id", "turn_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatTurnRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "turn_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @Column(nullable = false, length = 10)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 50)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(length = 20)
    private String provider;

    /** AI 에 보낸 요청 원문 JSON (AI 턴만) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request")
    private String request;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    public ChatTurnRecord(Long attemptId, int turnNo, String role, String content, String model, Integer inputTokens,
                          Integer outputTokens, Instant createdAt, String provider, String request, Integer latencyMs) {
        this.attemptId = attemptId;
        this.turnNo = turnNo;
        this.role = role;
        this.content = content;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = createdAt;
        this.provider = provider;
        this.request = request;
        this.latencyMs = latencyMs;
    }

}
