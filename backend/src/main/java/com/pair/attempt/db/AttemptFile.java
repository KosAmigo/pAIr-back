package com.pair.attempt.db;

import java.time.Instant;

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

/** 응시 작업 파일 (ERD attempt_files). 경로마다 최신 내용 한 행 */
@Entity
@Table(name = "attempt_files", uniqueConstraints = @UniqueConstraint(columnNames = {"attempt_id", "path"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_file_id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AttemptFile(Long attemptId, String path, String content, Instant at) {
        this.attemptId = attemptId;
        this.path = path;
        this.content = content;
        this.updatedAt = at;
    }

    public void update(String content, Instant at) {
        this.content = content;
        this.updatedAt = at;
    }

}
