package com.pair.domain.attempt.entity;

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

/** 체크포인트 파일 (ERD checkpoint_files): 바로 앞 체크포인트(첫 번째는 과제 원본)에서 바뀐 파일만 한 행 */
@Entity
@Table(name = "checkpoint_files", uniqueConstraints = @UniqueConstraint(columnNames = {"checkpoint_id", "path"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckpointFileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkpoint_file_id")
    private Long id;

    @Column(name = "checkpoint_id", nullable = false)
    private Long checkpointId;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    public CheckpointFileRecord(Long checkpointId, String path, String content) {
        this.checkpointId = checkpointId;
        this.path = path;
        this.content = content;
    }
}
