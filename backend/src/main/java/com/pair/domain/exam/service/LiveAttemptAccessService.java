package com.pair.domain.exam.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;
import com.pair.global.security.InviteTokenService;

/** 실전 응시 하나를 다룰 수 있는 사람인지 확인한다 (/api/attempts/{id}/**). */
@Service
public class LiveAttemptAccessService {

    private final ExamParticipantRepository participants;
    private final ConsoleAccessService console;

    public LiveAttemptAccessService(ExamParticipantRepository participants, ConsoleAccessService console) {
        this.participants = participants;
        this.console = console;
    }

    /** 초대 토큰의 주인이 이 응시의 대상자인지. 다시 발급돼 무효가 된 옛 토큰도 막힌다 */
    @Transactional(readOnly = true)
    public void requireParticipant(String token, Long participantId) {
        boolean owner = participantId != null && InviteTokenService.looksValid(token)
                && participants.findByInviteTokenHash(InviteTokenService.hash(token))
                        .filter(p -> p.getId().equals(participantId)).isPresent();
        if (!owner) throw new ForbiddenException("입장 링크를 확인할 수 없습니다. 메일의 입장 링크로 다시 들어와 주세요.");
    }

    /** 이 응시가 속한 회차의 결과를 볼 수 있는지 (그 회사의 매니저, 관리자). 콘솔의 결과 화면과 같은 규칙이다 */
    @Transactional(readOnly = true)
    public void requireResultViewer(CurrentUser user, Long participantId) {
        console.requireManager(user);
        ExamParticipant p = participantId == null ? null : participants.findById(participantId).orElse(null);
        if (p == null) throw new ForbiddenException("이 응시의 결과를 볼 수 없습니다.");
        console.requireSession(user, p.getSession().getId());
    }
}
