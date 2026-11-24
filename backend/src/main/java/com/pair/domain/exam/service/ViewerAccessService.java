package com.pair.domain.exam.service;

import java.time.Clock;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.dto.ViewerAccessDto;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;
import com.pair.global.security.WebSocketUserResolver;

/**
 * 감독관이 응시자 영상을 열 수 있는지 확인한다 (WebSocket 의 watch). 콘솔 API 와 같은 규칙이다.
 *  - 로그인한 감독관·매니저·관리자만, 자기 회사 회차의 응시자만 (다른 회사 회차는 있는지도 알리지 않고 DENIED)
 *  - 감독을 쓰는 회차이고, 진행 중(LIVE)이며, 응시자가 입장했고 제출 전일 때만 ALLOWED
 *  - 시작 전이거나 아직 입장 전이면 OFFLINE (곧 가능하다), 끝났거나 취소됐거나 제출했으면 ERROR
 */
@Service
public class ViewerAccessService {

    private final WebSocketUserResolver users;
    private final ConsoleAccessService access;
    private final ExamParticipantRepository participants;
    private final ParticipantProgressService progress;
    private final Clock clock;

    public ViewerAccessService(WebSocketUserResolver users, ConsoleAccessService access, ExamParticipantRepository participants,
            ParticipantProgressService progress, Clock clock) {
        this.users = users;
        this.access = access;
        this.participants = participants;
        this.progress = progress;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ViewerAccessDto check(Map<String, String> auth, Long sessionId, Long participantId) {
        Optional<CurrentUser> found = users.resolve(auth);
        if (found.isEmpty()) return ViewerAccessDto.denied();
        CurrentUser user = found.get();
        try {
            access.requireSession(user, sessionId);
        } catch (ForbiddenException | NoSuchElementException notAllowed) {
            return ViewerAccessDto.denied();
        }
        Optional<ExamParticipant> target = participants.findById(participantId).filter(p -> p.getSession().getId().equals(sessionId));
        if (target.isEmpty()) return ViewerAccessDto.denied();
        ExamParticipant p = target.get();
        ExamSession s = p.getSession();
        if (!s.isProctoring()) return ViewerAccessDto.error("감독을 쓰지 않는 회차입니다.", user.userId());
        switch (s.phase(clock.instant())) {
            case CANCELED -> { return ViewerAccessDto.error("취소된 회차입니다.", user.userId()); }
            case ENDED -> { return ViewerAccessDto.error("이미 끝난 시험입니다.", user.userId()); }
            case UPCOMING -> { return ViewerAccessDto.offline(user.userId()); }
            case LIVE -> { /* 아래에서 응시자 상태를 본다 */ }
        }
        if (p.getEnteredAt() == null) return ViewerAccessDto.offline(user.userId());
        if (progress.of(p).finished()) return ViewerAccessDto.error("이미 제출한 응시자입니다.", user.userId());
        return ViewerAccessDto.allowed(user.userId());
    }
}
