package com.pair.exam.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 회차 대상자. 초대 토큰(입장 링크)으로만 입장한다.
 * 시험 시작·제출은 과제별 응시(ERD attempts, participant_id)에 남는다 → ParticipantProgress
 */
@Entity
@Table(name = "exam_participants")
public class ExamParticipant {

    public enum InviteStatus { PENDING, SENT, FAILED }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ExamSession session;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    /** 입장 링크 토큰의 SHA-256 (hex). 원문은 메일에만 담고 저장하지 않는다 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "invite_token_hash", nullable = false, unique = true, length = 64)
    private String inviteTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "invite_status", nullable = false, length = 10)
    private InviteStatus inviteStatus = InviteStatus.PENDING;

    @Column(name = "invite_error", length = 255)
    private String inviteError;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    /** 시작 하루 전 알림 메일을 보낸(보내려고 잡은) 시각 */
    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "entered_at")
    private Instant enteredAt;


    protected ExamParticipant() {}

    public ExamParticipant(ExamSession session, String email, String name, String inviteTokenHash, Instant invitedAt) {
        this.session = session;
        this.email = email;
        this.name = name;
        this.inviteTokenHash = inviteTokenHash;
        this.invitedAt = invitedAt;
    }

    /** 초대 메일을 다시 보낼 때: 새 토큰으로 바꿔 이전 링크를 무효로 하고, 발송 상태를 처음으로 되돌린다 */
    public void reissueInvite(String newTokenHash, Instant at) {
        this.inviteTokenHash = newTokenHash;
        this.invitedAt = at;
        this.inviteStatus = InviteStatus.PENDING;
        this.inviteError = null;
    }


    /** 처음 입장한 시각만 남긴다 (새로 고침해도 바뀌지 않음) */
    public void markEntered(Instant at) {
        if (enteredAt == null) enteredAt = at;
    }

    /** invite_error 컬럼 길이에 맞춘다 */
    public static String trimInviteError(String error) {
        return error == null ? null : error.substring(0, Math.min(255, error.length()));
    }

    public Long getId() { return id; }
    public ExamSession getSession() { return session; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getInviteTokenHash() { return inviteTokenHash; }
    public InviteStatus getInviteStatus() { return inviteStatus; }
    public String getInviteError() { return inviteError; }
    public Instant getInvitedAt() { return invitedAt; }
    public Instant getRemindedAt() { return remindedAt; }
    public Instant getEnteredAt() { return enteredAt; }
}
