package com.pair.exam.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 감독 이벤트 1건. 카메라 이벤트는 사진 1장(snapshotKey)이 붙을 수 있다. */
@Entity
@Table(name = "proctor_events")
public class ProctorEvent {

    /** 브라우저 이벤트(frontend/anti-cheating)와 카메라 이벤트(cam-proctor)를 함께 받는다 */
    public enum Type {
        TAB_HIDDEN, WINDOW_BLUR, MOUSE_LEAVE, PASTE, FULLSCREEN_EXIT,
        NO_FACE, MULTI_FACE, HEAD_TURN, HEAD_DOWN, GAZE_AWAY, TORSO_SHIFT, TORSO_TURN,
        HAND_MISSING, HAND_MOVED, ARM_MOVE, PHONE, EXTRA_SCREEN, BOOK, PHONE_DISCONNECTED
    }

    public enum Source { BROWSER, PC_CAM, PHONE_CAM }

    /** 담당자 판정. 감독 이벤트는 자동 탈락이 아니라 사람이 확인하는 기록이다 */
    public enum Review { OK, SUSPECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proctor_event_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "snapshot_key", length = 255)
    private String snapshotKey;

    @Column(name = "snapshot_bytes", nullable = false)
    private int snapshotBytes;

    @Column(name = "snapshot_deleted_at")
    private Instant snapshotDeletedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Review review;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    /** 판정한 회원 ID. users 가 DB 로 옮겨지면 외래 키를 건다 */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected ProctorEvent() {}

    public ProctorEvent(Long participantId, Type type, Source source, Instant startedAt, int durationMs,
            BigDecimal confidence, String detail, Instant receivedAt) {
        this.participantId = participantId;
        this.type = type;
        this.source = source;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.confidence = confidence;
        this.detail = detail;
        this.receivedAt = receivedAt;
    }

    public void attachSnapshot(String key, int bytes) {
        this.snapshotKey = key;
        this.snapshotBytes = bytes;
    }

    public void markSnapshotDeleted(Instant at) {
        this.snapshotDeletedAt = at;
    }

    /** 판정을 남기거나 바꾼다. review 가 null 이면 판정을 지워 미확인으로 되돌린다 */
    public void review(Review review, String note, Long reviewerId, Instant at) {
        if (review != null && reviewerId == null) throw new IllegalArgumentException("판정한 사람을 알 수 없습니다.");
        this.review = review;
        this.reviewNote = review == null ? null : note;
        this.reviewedBy = review == null ? null : reviewerId;
        this.reviewedAt = review == null ? null : at;
    }

    public Long getId() { return id; }
    public Long getParticipantId() { return participantId; }
    public Type getType() { return type; }
    public Source getSource() { return source; }
    public Instant getStartedAt() { return startedAt; }
    public int getDurationMs() { return durationMs; }
    public BigDecimal getConfidence() { return confidence; }
    public String getDetail() { return detail; }
    public String getSnapshotKey() { return snapshotKey; }
    public int getSnapshotBytes() { return snapshotBytes; }
    public Instant getSnapshotDeletedAt() { return snapshotDeletedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Review getReview() { return review; }
    public String getReviewNote() { return reviewNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
}
