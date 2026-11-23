package com.pair.domain.exam.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 콘솔이 응시자에게 보낸 안내 메시지 1건 (일방향). 응시자가 확인하면 readAt 이 찍힌다. */
@Entity
@Table(name = "proctor_notices")
public class ProctorNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected ProctorNotice() {}

    public ProctorNotice(Long participantId, String message, Long sentBy, Instant sentAt) {
        this.participantId = participantId;
        this.message = message;
        this.sentBy = sentBy;
        this.sentAt = sentAt;
    }

    /** 처음 읽은 시각을 지킨다 (두 번 눌러도 바뀌지 않는다) */
    public void markRead(Instant at) {
        if (readAt == null) readAt = at;
    }

    public Long getId() { return id; }
    public Long getParticipantId() { return participantId; }
    public String getMessage() { return message; }
    public Long getSentBy() { return sentBy; }
    public Instant getSentAt() { return sentAt; }
    public Instant getReadAt() { return readAt; }
}
