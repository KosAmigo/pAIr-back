package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.Map;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;

/**
 * startedAt·submittedAt·attempts 는 과제별 응시(attempts)에서 계산한다 (ParticipantProgress). attempts: 과제 code → 응시 ID
 * linkExpired: 연결 유예(5분)를 넘겨 입장 링크가 만료된 상태 (진행 중인 시험에서만 true. 콘솔의 "링크 복구" 버튼 조건)
 */
public record ParticipantResponse(Long id, String name, String email, ExamParticipant.InviteStatus inviteStatus,
        String inviteError, Instant invitedAt, Instant remindedAt, Instant enteredAt, Instant startedAt,
        Instant submittedAt, Map<String, String> attempts, boolean linkExpired) {

    public static ParticipantResponse from(ExamParticipant p, com.pair.domain.exam.service.ParticipantProgress.Progress pr, Instant now) {
        boolean live = p.getSession().phase(now) == ExamSession.Phase.LIVE;
        boolean expired = live && com.pair.domain.exam.service.ConnectionGrace.isExpired(p, pr.started(), pr.finished(), now);
        return new ParticipantResponse(p.getId(), p.getName(), p.getEmail(), p.getInviteStatus(), p.getInviteError(),
                p.getInvitedAt(), p.getRemindedAt(), p.getEnteredAt(), pr.startedAt(), pr.submittedAt(), pr.attempts(), expired);
    }
}
