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

/** 환경 점검 사진 1장 (방 360도의 한 방향, 또는 책상 위). 파일은 저장소(storageKey)에 있다. */
@Entity
@Table(name = "env_scan_photos")
public class EnvScanPhoto {

    public enum Stage { ROOM, DESK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "env_scan_photo_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Stage stage;

    @Column(name = "angle_deg")
    private Short angleDeg;

    @Column(length = 400)
    private String objects;

    @Column(name = "phone_suspect", nullable = false)
    private boolean phoneSuspect;

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected EnvScanPhoto() {
    }

    public EnvScanPhoto(Long participantId, Stage stage, Short angleDeg, String objects, boolean phoneSuspect, Instant capturedAt) {
        this.participantId = participantId;
        this.stage = stage;
        this.angleDeg = angleDeg;
        this.objects = objects;
        this.phoneSuspect = phoneSuspect;
        this.capturedAt = capturedAt;
    }

    public void attachFile(String key, int sizeBytes) {
        this.storageKey = key;
        this.sizeBytes = sizeBytes;
    }

    public void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    public Long getId() { return id; }
    public Long getParticipantId() { return participantId; }
    public Stage getStage() { return stage; }
    public Short getAngleDeg() { return angleDeg; }
    public String getObjects() { return objects; }
    public boolean isPhoneSuspect() { return phoneSuspect; }
    public String getStorageKey() { return storageKey; }
    public int getSizeBytes() { return sizeBytes; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
