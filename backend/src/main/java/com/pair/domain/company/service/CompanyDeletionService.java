package com.pair.domain.company.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.company.dto.CompanyDeletionResponse;
import com.pair.domain.company.entity.Company;
import com.pair.domain.company.entity.CompanyAccountEvent;
import com.pair.domain.company.entity.CompanyInvite;
import com.pair.domain.company.repository.CompanyAccountEventRepository;
import com.pair.domain.company.repository.CompanyInviteRepository;
import com.pair.domain.company.repository.CompanyRepository;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.service.ExamSessionService;
import com.pair.global.error.ConflictException;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;

/**
 * 관리자의 회사 삭제 (소프트 삭제).
 * 회차·응시·감독 기록·결제가 회사와 멤버를 참조하므로 행은 지우지 않고, 회사는 deleted_at 을 남기고
 * 멤버는 본인 탈퇴와 같은 방식(이메일 탈퇴 해시)으로 정리한다. 그래서 같은 사람이 나중에 개인 회원으로 가입하거나
 * 다른 회사의 기업 계정·감독관으로 들어오면 그 행이 되살아난다.
 * 감독 사진은 지우지 않는다: 회차 종료 뒤 보관 기간이 지나면 기존 주기 작업이 지운다.
 */
@Service
public class CompanyDeletionService {

    private static final Logger log = LoggerFactory.getLogger(CompanyDeletionService.class);

    private final CompanyRepository companies;
    private final AuthAccountRepository accounts;
    private final AuthService auth;
    private final CompanyInviteRepository invites;
    private final ExamSessionRepository sessions;
    private final ExamSessionService examSessions;
    private final CompanyAccountEventRepository events;
    private final Clock clock;

    public CompanyDeletionService(CompanyRepository companies, AuthAccountRepository accounts, AuthService auth,
            CompanyInviteRepository invites, ExamSessionRepository sessions, ExamSessionService examSessions,
            CompanyAccountEventRepository events, Clock clock) {
        this.companies = companies;
        this.accounts = accounts;
        this.auth = auth;
        this.invites = invites;
        this.sessions = sessions;
        this.examSessions = examSessions;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public CompanyDeletionResponse delete(long companyId, CurrentUser admin) {
        if (!admin.isAdmin()) throw new ForbiddenException("관리자 권한이 필요합니다.");
        Company company = companies.lockById(companyId)
                .orElseThrow(() -> new NoSuchElementException("회사를 찾을 수 없습니다."));
        if (company.isDeleted()) throw new ConflictException("이미 삭제한 회사입니다.");
        Instant now = clock.instant();

        List<ExamSession> companySessions = sessions.findByCompanyIdOrderByStartsAtDesc(companyId);
        if (companySessions.stream().anyMatch(s -> s.phase(now) == ExamSession.Phase.LIVE)) {
            throw new ConflictException("진행 중인 회차가 있어 회사를 삭제할 수 없습니다. 회차가 끝난 뒤 다시 시도해 주세요.");
        }
        // 예정 회차는 매니저가 취소한 것과 같이 처리한다 (대상자에게 취소 안내 메일)
        List<ExamSession> upcoming = companySessions.stream()
                .filter(s -> s.phase(now) == ExamSession.Phase.UPCOMING).toList();
        upcoming.forEach(s -> examSessions.cancel(s.getId()));

        List<CompanyInvite> pending = invites.lockPendingByCompanyId(companyId);
        pending.forEach(i -> i.revoke(now));

        // 첫 로그인 전(PENDING) 멤버도 이메일을 쥐고 있으므로 같이 정리한다
        List<AuthAccount> members = accounts.lockByCompanyId(companyId).stream()
                .filter(a -> !"WITHDRAWN".equals(a.getMemberStatus())).toList();
        for (AuthAccount member : members) {
            auth.withdrawAccount(member, now);
            accounts.flush(); // 탈퇴 해시가 겹치는지 다음 멤버를 볼 때 DB 에 반영돼 있어야 한다
        }

        company.delete(now);
        events.save(new CompanyAccountEvent(companyId, "COMPANY_DELETED", admin.userId(), null,
                "withdrawnMembers=" + members.size() + ", canceledSessions=" + upcoming.size()
                        + ", revokedInvites=" + pending.size(), now));
        log.info("회사 삭제: companyId={}, 탈퇴 멤버 {}명, 취소 회차 {}개, 무효 초대 {}개",
                companyId, members.size(), upcoming.size(), pending.size());
        return new CompanyDeletionResponse(companyId, members.size(), upcoming.size(), pending.size());
    }
}
