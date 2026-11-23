package com.pair.exam.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 이벤트 증거 구간의 사진 1장 (이벤트 앞 8초·뒤 4초 중 한 시점, PC 웹캠 또는 휴대폰).
 * 사진 파일은 파일 저장소에 있고 DB 에는 키·크기·시점만 둔다. 같은 (이벤트, 출처, 시점)은 한 장만 있다.
 */
@Entity
@Table(name = "proctor_event_frames",
        uniqueConstraints = @UniqueConstraint(name = "uq_event_frame", columnNames = {"event_id", "source", "offset_ms"}))
public class ProctorEventFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ProctorEvent.Source source;

    /** 이벤트 시작 기준 상대 시각(ms). 음수는 이벤트 전 */
    @Column(name = "offset_ms", nullable = false)
    private int offsetMs;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ProctorEventFrame() {}

    public ProctorEventFrame(Long sessionId, Long eventId, ProctorEvent.Source source, int offsetMs, String storageKey, int sizeBytes, Instant createdAt) {
        this.sessionId = sessionId;
        this.eventId = eventId;
        this.source = source;
        this.offsetMs = offsetMs;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    /** 저장한 뒤 번호가 정해지면 파일 키를 붙인다 */
    public void assignKey(String key) {
        this.storageKey = key;
    }

    public void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getEventId() { return eventId; }
    public ProctorEvent.Source getSource() { return source; }
    public int getOffsetMs() { return offsetMs; }
    public String getStorageKey() { return storageKey; }
    public int getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
