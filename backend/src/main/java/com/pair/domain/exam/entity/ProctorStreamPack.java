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
import jakarta.persistence.UniqueConstraint;

/**
 * 사진 상시 저장 묶음 1개 (한 채널의 10초 정렬 구간, 초당 1장). 파일은 파일 저장소(exam-stream/...)에 있고 DB 에는 색인만 둔다.
 * 같은 구간을 다시 올리면 같은 키로 덮어쓰고 이 행을 갱신한다(멱등).
 */
@Entity
@Table(name = "proctor_stream_packs",
        uniqueConstraints = @UniqueConstraint(name = "uq_stream_pack", columnNames = {"participant_id", "channel", "start_at"}))
public class ProctorStreamPack {

    /** 사진을 보낸 채널. 이벤트의 출처(source)와 WEBCAM=PC_CAM, PHONE=PHONE_CAM 으로 대응한다 */
    public enum Channel {
        WEBCAM(ProctorEvent.Source.PC_CAM, 1, "webcam"), PHONE(ProctorEvent.Source.PHONE_CAM, 2, "phone");

        private final ProctorEvent.Source source;
        private final int code;
        private final String wire;

        Channel(ProctorEvent.Source source, int code, String wire) {
            this.source = source;
            this.code = code;
            this.wire = wire;
        }

        public ProctorEvent.Source source() { return source; }
        /** .pack 파일 헤더·추론 서버 프레임의 채널 번호 (1=webcam, 2=phone) */
        public int code() { return code; }
        /** 저장소 키·콘솔 쿼리에 쓰는 이름 */
        public String wire() { return wire; }

        public static Channel ofSource(ProctorEvent.Source source) {
            return source == ProctorEvent.Source.PHONE_CAM ? PHONE : WEBCAM;
        }

        public static java.util.Optional<Channel> ofWire(String wire) {
            for (Channel c : values()) if (c.wire.equals(wire)) return java.util.Optional.of(c);
            return java.util.Optional.empty();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pack_id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Channel channel;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "frame_count", nullable = false)
    private short frameCount;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ProctorStreamPack() {}

    public ProctorStreamPack(Long sessionId, Long participantId, Channel channel, Instant startAt, Instant endAt, int frameCount,
            int sizeBytes, String storageKey, Instant createdAt) {
        this.sessionId = sessionId;
        this.participantId = participantId;
        this.channel = channel;
        this.startAt = startAt;
        this.endAt = endAt;
        this.frameCount = (short) frameCount;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    /** 같은 구간을 다시 올렸을 때 (사진이 더 모여서) 색인을 맞춘다 */
    public void reuploaded(int frameCount, int sizeBytes) {
        this.frameCount = (short) frameCount;
        this.sizeBytes = sizeBytes;
    }

    public void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getParticipantId() { return participantId; }
    public Channel getChannel() { return channel; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public int getFrameCount() { return frameCount; }
    public int getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
