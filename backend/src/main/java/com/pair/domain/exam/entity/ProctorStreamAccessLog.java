package com.pair.domain.exam.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 감독 중단 구간 사진 열람 기록 1건: 누가·언제·누구의·어느 채널·어느 범위·몇 장. 감독관은 지울 수 없다(지우는 API 가 없다) */
@Entity
@Table(name = "proctor_stream_access_logs")
public class ProctorStreamAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "viewer_id", nullable = false)
    private Long viewerId;

    @Column(nullable = false, length = 8)
    private String channel;

    @Column(name = "range_from", nullable = false)
    private Instant rangeFrom;

    @Column(name = "range_to", nullable = false)
    private Instant rangeTo;

    @Column(name = "frame_count", nullable = false)
    private int frameCount;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    protected ProctorStreamAccessLog() {}

    public ProctorStreamAccessLog(Long sessionId, Long participantId, Long eventId, Long viewerId, String channel,
            Instant rangeFrom, Instant rangeTo, int frameCount, Instant accessedAt) {
        this.sessionId = sessionId;
        this.participantId = participantId;
        this.eventId = eventId;
        this.viewerId = viewerId;
        this.channel = channel;
        this.rangeFrom = rangeFrom;
        this.rangeTo = rangeTo;
        this.frameCount = frameCount;
        this.accessedAt = accessedAt;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getParticipantId() { return participantId; }
    public Long getEventId() { return eventId; }
    public Long getViewerId() { return viewerId; }
    public String getChannel() { return channel; }
    public Instant getRangeFrom() { return rangeFrom; }
    public Instant getRangeTo() { return rangeTo; }
    public int getFrameCount() { return frameCount; }
    public Instant getAccessedAt() { return accessedAt; }
}
