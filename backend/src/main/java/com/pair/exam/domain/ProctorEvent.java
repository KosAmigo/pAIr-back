package com.pair.exam.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
    /** 감지 위치는 종류마다 정해져 있다 (ERD proctor_event_types.source 와 같다) */
    public enum Type {
        TAB_HIDDEN(Source.BROWSER), WINDOW_BLUR(Source.BROWSER), MOUSE_LEAVE(Source.BROWSER), PASTE(Source.BROWSER),
        FULLSCREEN_EXIT(Source.BROWSER),
        NO_FACE(Source.PC_CAM), MULTI_FACE(Source.PC_CAM), HEAD_TURN(Source.PC_CAM), HEAD_DOWN(Source.PC_CAM),
        GAZE_AWAY(Source.PC_CAM), TORSO_SHIFT(Source.PC_CAM), TORSO_TURN(Source.PC_CAM),
        HAND_MISSING(Source.PHONE_CAM), HAND_MOVED(Source.PHONE_CAM), ARM_MOVE(Source.PHONE_CAM), PHONE(Source.PHONE_CAM),
        EXTRA_SCREEN(Source.PHONE_CAM), BOOK(Source.PHONE_CAM), PHONE_DISCONNECTED(Source.PHONE_CAM),
        /** 서버가 직접 남기는 기록 (연결 끊김·링크 만료·담당자의 링크 복구). 응시자 화면이 보내는 이벤트로는 받지 않는다 */
        CONNECTION_LOST(Source.BROWSER), LINK_EXPIRED(Source.BROWSER), LINK_RESTORED(Source.BROWSER);

        private final Source source;

        Type(Source source) {
            this.source = source;
        }

        public Source source() {
            return source;
        }
    }

    public enum Source { BROWSER, PC_CAM, PHONE_CAM }

    /** 담당자 판정. 감독 이벤트는 자동 탈락이 아니라 사람이 확인하는 기록이다 */
    public enum Review { OK, SUSPECT }

    /** 서버가 사진을 받지 않은 이유 */
    public enum SnapshotSkipReason { LIMIT_EXCEEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proctor_event_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** 감지 세부 정보 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String detail;

    @Column(name = "snapshot_key", length = 255)
    private String snapshotKey;

    @Column(name = "snapshot_bytes", nullable = false)
    private int snapshotBytes;

    @Column(name = "snapshot_deleted_at")
    private Instant snapshotDeletedAt;

    /** 사진을 받지 않은 서버 쪽 사유 (상한 초과). 사진이 붙으면 비운다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_skip_reason", length = 20)
    private SnapshotSkipReason snapshotSkipReason;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 브라우저가 만든 이벤트 고유값. 재전송해도 같은 이벤트로 알아보게 한다 (없을 수 있다) */
    @Column(name = "client_id", length = 36)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Review review;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    /** 판정한 회원 ID (users 외래 키) */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected ProctorEvent() {}

    public ProctorEvent(Long participantId, Type type, Instant startedAt, int durationMs,
            BigDecimal confidence, String detail, Instant receivedAt) {
        this.participantId = participantId;
        this.type = type;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.confidence = confidence;
        this.detail = detail;
        this.receivedAt = receivedAt;
    }

    /** 사진 상한에 막혀 사진을 받지 않았음을 기록한다 */
    public void skipSnapshot(SnapshotSkipReason reason) {
        this.snapshotSkipReason = reason;
    }

    public void assignClientId(String clientId) {
        this.clientId = clientId;
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
    public Source getSource() { return type.source(); }
    public Instant getStartedAt() { return startedAt; }
    public int getDurationMs() { return durationMs; }
    public BigDecimal getConfidence() { return confidence; }
    public String getDetail() { return detail; }
    public String getSnapshotKey() { return snapshotKey; }
    public int getSnapshotBytes() { return snapshotBytes; }
    public Instant getSnapshotDeletedAt() { return snapshotDeletedAt; }
    public SnapshotSkipReason getSnapshotSkipReason() { return snapshotSkipReason; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getClientId() { return clientId; }
    public Review getReview() { return review; }
    public String getReviewNote() { return reviewNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
}
