package com.pair.domain.company.service;

import com.pair.domain.company.dto.CompanyDetailResponse;
import com.pair.domain.company.dto.CompanyMemberResponse;
import com.pair.domain.company.dto.CompanyResponse;
import com.pair.domain.company.dto.ManagerReplacementResponse;
import com.pair.domain.company.dto.MemberListResponse;
import com.pair.domain.company.dto.ReplaceManagerRequest;
import com.pair.domain.company.entity.Company;
import com.pair.domain.company.entity.CompanyAccountEvent;
import com.pair.domain.company.repository.CompanyAccountEventRepository;
import com.pair.domain.company.repository.CompanyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.security.InviteTokenService;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;
import com.pair.global.security.CurrentUser.Role;
import com.pair.domain.auth.service.EmailVerificationService;
import com.pair.global.error.ForbiddenException;
import com.pair.domain.auth.repository.RefreshSessionRepository;

@Service
public class CompanyMemberService {

    private final CompanyRepository companies;
    private final AuthAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final CorporateAccountService corporateAccounts;
    private final CompanyAccountEventRepository events;
    private final EmailVerificationService emailVerification;

    public CompanyMemberService(CompanyRepository companies, AuthAccountRepository accounts,
            RefreshSessionRepository sessions, CorporateAccountService corporateAccounts,
            CompanyAccountEventRepository events, EmailVerificationService emailVerification) {
        this.companies = companies;
        this.accounts = accounts;
        this.sessions = sessions;
        this.corporateAccounts = corporateAccounts;
        this.events = events;
        this.emailVerification = emailVerification;
    }

    @Transactional(readOnly = true)
    public MemberListResponse membersForManager(CurrentUser user) {
        requireManager(user);
        return new MemberListResponse(user.companyId(), accountViews(user.companyId()));
    }

    @Transactional
    public void delegate(CurrentUser user, long proctorUserId) {
        requireManager(user);
        List<AuthAccount> companyAccounts = accounts.lockByCompanyId(user.companyId());
        AuthAccount manager = companyAccounts.stream().filter(a -> a.getId().equals(user.userId())
                && a.getRole() == Role.MANAGER && a.isActive()).findFirst()
                .orElseThrow(() -> new ForbiddenException("현재 회사 매니저가 아닙니다."));
        AuthAccount proctor = companyAccounts.stream().filter(a -> a.getId() == proctorUserId
                && a.getRole() == Role.PROCTOR && a.isActive()).findFirst()
                .orElseThrow(() -> new NoSuchElementException("같은 회사의 활성 감독관을 찾을 수 없습니다."));
        if (!proctor.isEmailVerified() || proctor.isPasswordChangeRequired()) {
            throw new IllegalStateException("이메일 인증과 비밀번호 변경을 완료한 감독관만 매니저를 위임받을 수 있습니다.");
        }

        manager.changeRole(Role.PROCTOR);
        accounts.flush();
        proctor.changeRole(Role.MANAGER);
        accounts.flush();
        sessions.revokeAll(manager.getId());
        sessions.revokeAll(proctor.getId());
        events.save(new CompanyAccountEvent(user.companyId(), "MANAGER_DELEGATED", user.userId(),
                proctor.getId(), "formerManagerUserId=" + manager.getId(), Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> allCompanies() {
        return companies.findAll().stream().map(CompanyResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CompanyDetailResponse company(Long companyId) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NoSuchElementException("회사를 찾을 수 없습니다."));
        return new CompanyDetailResponse(CompanyResponse.from(company), accountViews(companyId));
    }

    @Transactional
    public ManagerReplacementResponse replaceManager(long companyId, CurrentUser admin,
            ReplaceManagerRequest request) {
        requireAdmin(admin);
        if (companies.findById(companyId).isEmpty()) throw new NoSuchElementException("회사를 찾을 수 없습니다.");
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (accounts.existsByEmailIgnoreCase(email)) throw new ConflictException("이미 가입된 이메일입니다.");
        List<AuthAccount> companyAccounts = accounts.lockByCompanyId(companyId);
        List<AuthAccount> activeManagers = companyAccounts.stream()
                .filter(a -> a.getRole() == Role.MANAGER && a.isActive()).toList();
        if (activeManagers.size() > 1) throw new IllegalStateException("회사의 활성 매니저가 여러 명입니다.");
        AuthAccount oldManager = activeManagers.isEmpty() ? null : activeManagers.get(0);
        if (oldManager != null) {
            emailVerification.removeForEmail(oldManager.getEmail());
            oldManager.deactivate("withdrawn+" + InviteTokenService.next() + "@invalid.local",
                    CorporateAccountService.newInvalidPasswordHash(), Instant.now());
            accounts.flush();
            sessions.revokeAll(oldManager.getId());
        }
        AuthAccount newManager = corporateAccounts.create(email, request.name(), Role.MANAGER, companyId);
        events.save(new CompanyAccountEvent(companyId, "ADMIN_MANAGER_REPLACED", admin.userId(),
                newManager.getId(), oldManager == null ? "no active former manager" :
                        "formerManagerUserId=" + oldManager.getId(), Instant.now()));
        return new ManagerReplacementResponse(companyId, newManager.getId(),
                "새 매니저 계정을 생성했습니다. 인증 안내 메일을 곧 발송합니다.");
    }

    @Transactional
    public AuthAccount reissue(Long userId, CurrentUser admin) {
        requireAdmin(admin);
        // 잠그기 전 값으로 판단하면 그 사이 탈퇴·교체된 계정을 재발급할 수 있다
        AuthAccount account = accounts.lockById(userId)
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다."));
        if (account.getCompanyId() == null) throw new IllegalArgumentException("기업 계정이 아닙니다.");
        AuthAccount updated = corporateAccounts.reissue(userId);
        events.save(new CompanyAccountEvent(updated.getCompanyId(), "ONBOARDING_REISSUED",
                admin.userId(), updated.getId(), null, Instant.now()));
        return updated;
    }

    private List<CompanyMemberResponse> accountViews(Long companyId) {
        return accounts.findByCompanyIdOrderByRoleAscNameAsc(companyId).stream()
                .map(CompanyMemberResponse::from).toList();
    }

    private static void requireManager(CurrentUser user) {
        if (user.role() != Role.MANAGER || user.companyId() == null) {
            throw new ForbiddenException("매니저 권한이 필요합니다.");
        }
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new ForbiddenException("관리자 권한이 필요합니다.");
    }
}
