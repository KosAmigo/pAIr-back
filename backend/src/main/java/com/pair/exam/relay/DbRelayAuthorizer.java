package com.pair.exam.relay;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.service.InviteTokens;

@Component
class DbRelayAuthorizer implements RelayAuthorizer {

    /** 시험이 끝난 뒤에도 늦게 도착하는 이벤트를 받는 시간(ProctorService)과 같이 맞춘다 */
    private static final Duration LATE_GRACE = Duration.ofMinutes(30);

    private final ExamParticipantRepository participants;
    private final Clock clock;

    DbRelayAuthorizer(ExamParticipantRepository participants, Clock clock) {
        this.participants = participants;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean authorize(String inviteToken) {
        if (!InviteTokens.looksValid(inviteToken)) return false;
        return participants.findByInviteTokenHash(InviteTokens.hash(inviteToken))
                .map(p -> p.getSession().isProctoring() && p.getSession().getCanceledAt() == null
                        && !clock.instant().isAfter(p.getSession().getEndsAt().plus(LATE_GRACE)))
                .orElse(false);
    }
}
