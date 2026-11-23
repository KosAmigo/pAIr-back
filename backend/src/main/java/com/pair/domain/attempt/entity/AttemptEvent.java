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

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 작업 기록 (ERD attempt_events). type 은 START, SAVE, BUILD, RUN, CHAT, CHAT_EMPTY, AI_EDIT, SUBMIT, FOLLOWUPS, FIRST_VALID */
@Entity
@Table(name = "attempt_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(nullable = false, length = 20)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AttemptEvent(Long attemptId, String type, String payload, Instant createdAt) {
        this.attemptId = attemptId;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

}
