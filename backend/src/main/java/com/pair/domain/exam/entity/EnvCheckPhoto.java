package com.pair.domain.exam.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 환경 점검 사진 1장 (왼쪽 귀·오른쪽 귀·손목). 대상자·단계마다 한 장이고 파일은 저장소(storageKey)에 있다. */
@Entity
@Table(name = "env_check_photos")
public class EnvCheckPhoto {

    public enum Stage { EAR_LEFT, EAR_RIGHT, WRIST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "env_check_photo_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Stage stage;

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected EnvCheckPhoto() {
    }

    public EnvCheckPhoto(Long participantId, Stage stage, Instant capturedAt) {
        this.participantId = participantId;
        this.stage = stage;
        this.capturedAt = capturedAt;
    }

    public void attachFile(String key, int sizeBytes) {
        this.storageKey = key;
        this.sizeBytes = sizeBytes;
    }

    /** 다시 찍은 사진으로 바꾼다 (같은 파일 키를 덮어쓰므로 지웠다는 표시도 해제) */
    public void replace(int sizeBytes, Instant capturedAt) {
        this.sizeBytes = sizeBytes;
        this.capturedAt = capturedAt;
        this.deletedAt = null;
    }

    public void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    public Long getId() { return id; }
    public Long getParticipantId() { return participantId; }
    public Stage getStage() { return stage; }
    public String getStorageKey() { return storageKey; }
    public int getSizeBytes() { return sizeBytes; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
