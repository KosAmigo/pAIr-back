package com.pair.domain.exam.service;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;

import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.global.security.CurrentUser;
import com.pair.global.security.CurrentUser.Role;
import com.pair.global.error.ForbiddenException;

/**
 * 기업 콘솔 권한 확인 (회사 분리).
 * MANAGER: 자기 회사 회차를 만들고 고치고 결과를 본다. PROCTOR: 자기 회사 회차의 감독(이벤트·사진·판정)만. ADMIN: 모든 회사.
 * 다른 회사의 회차는 있는지조차 알리지 않도록 404 로 막는다.
 */
@Component
public class ConsoleAccessService {

    private final ExamSessionRepository sessions;

    public ConsoleAccessService(ExamSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** 회차 만들기·고치기·대상자·결과 */
    public void requireManager(CurrentUser user) {
        if (user.role() != Role.MANAGER && user.role() != Role.ADMIN) throw new ForbiddenException("회차 관리 권한이 없습니다.");
    }

    /** 회차 보기·감독 */
    public void requireConsole(CurrentUser user) {
        if (user.role() == Role.USER) throw new ForbiddenException("기업 콘솔 권한이 없습니다.");
    }

    /** 이 회차가 내 회사 것인지 (관리자는 통과) */
    public void requireSession(CurrentUser user, Long sessionId) {
        requireConsole(user);
        Long company = sessions.findCompanyIdById(sessionId).orElseThrow(() -> new NoSuchElementException("없는 회차입니다: " + sessionId));
        if (!user.belongsTo(company)) throw new NoSuchElementException("없는 회차입니다: " + sessionId);
    }

    /** 요청에 적힌 회사 대신 로그인한 회사로. 관리자만 회사를 골라 넣을 수 있다 */
    public Long companyFor(CurrentUser user, Long requested) {
        if (user.isAdmin()) {
            if (requested == null) throw new IllegalArgumentException("관리자는 companyId 를 넣어야 합니다.");
            return requested;
        }
        return user.companyId();
    }
}
