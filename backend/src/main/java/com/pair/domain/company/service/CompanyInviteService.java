package com.pair.domain.company.service;

import com.pair.domain.company.dto.InviteAcceptRequest;
import com.pair.domain.company.dto.InviteInfoResponse;
import com.pair.domain.company.entity.Company;
import com.pair.domain.company.entity.CompanyInvite;
import com.pair.domain.company.repository.CompanyInviteRepository;
import com.pair.domain.company.repository.CompanyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.security.InviteTokenService;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;
import com.pair.domain.auth.service.EmailVerificationService;
import com.pair.global.error.UnauthorizedException;

@Service
public class CompanyInviteService {

    private static final Duration INVITE_LIFETIME = Duration.ofDays(7);

    private final CompanyInviteRepository invites;
    private final CompanyRepository companies;
    private final AuthAccountRepository accounts;
    private final CorporateAccountService corporateAccounts;
    private final CompanyAccountMailService mailer;
    private final EmailVerificationService emailVerification;
    private final AuthService auth;
    private final String inviteBaseUrl;

    public CompanyInviteService(CompanyInviteRepository invites, CompanyRepository companies, AuthAccountRepository accounts,
            CorporateAccountService corporateAccounts, CompanyAccountMailService mailer, EmailVerificationService emailVerification,
            AuthService auth, @Value("${pair.company.invite-url:https://localhost:5173/company-invite}") String inviteBaseUrl) {
        this.invites = invites;
        this.companies = companies;
        this.accounts = accounts;
        this.corporateAccounts = corporateAccounts;
        this.mailer = mailer;
        this.emailVerification = emailVerification;
        this.auth = auth;
        this.inviteBaseUrl = inviteBaseUrl;
    }

    @Transactional
    public void inviteProctor(CurrentUser manager, String rawEmail) {
        if (manager.role() != CurrentUser.Role.MANAGER || manager.companyId() == null) {
            throw new com.pair.global.error.ForbiddenException("감독관 초대 권한이 없습니다.");
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (accounts.existsByEmailIgnoreCase(email)) throw new ConflictException("이미 가입된 이메일입니다.");
        Company company = companies.findById(manager.companyId())
                .orElseThrow(() -> new NoSuchElementException("회사를 찾을 수 없습니다."));
        Instant now = Instant.now();
        for (CompanyInvite old : invites.lockPendingByEmail(email)) {
            if (old.getExpiresAt().isAfter(now) && !old.getCompanyId().equals(manager.companyId())) {
                throw new ConflictException("이 이메일에는 다른 회사의 미사용 초대가 있습니다.");
            }
            old.revoke(now);
        }
        // IDENTITY 키는 save 때 INSERT 가 바로 나간다. 이전 초대 폐기를 먼저 반영해야 미사용 초대 유니크 인덱스에 걸리지 않는다
        invites.flush();
        String token = InviteTokenService.next();
        try {
            invites.saveAndFlush(new CompanyInvite(company.getId(), email, CurrentUser.Role.PROCTOR, InviteTokenService.hash(token),
                    manager.userId(), now.plus(INVITE_LIFETIME), now));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이 이메일로 이미 초대가 진행 중입니다.");
        }
        mailer.sendProctorInvite(email, email, inviteUrl(token));
    }

    @Transactional(readOnly = true)
    public InviteInfoResponse inspect(String token) {
        CompanyInvite invite = findUsable(token);
        String companyName = companies.findById(invite.getCompanyId())
                .orElseThrow(() -> new NoSuchElementException("회사를 찾을 수 없습니다.")).getName();
        return new InviteInfoResponse(invite.getEmail(), invite.getRole().name(), companyName, invite.getExpiresAt());
    }

    @Transactional
    public void requestSignupCode(String token) {
        CompanyInvite invite = findUsable(token);
        if (accounts.existsByEmailIgnoreCase(invite.getEmail())) throw new ConflictException("이미 가입된 이메일입니다.");
        emailVerification.sendCode(invite.getEmail());
    }

    @Transactional
    public com.pair.domain.auth.dto.AuthResponse accept(InviteAcceptRequest request) {
        CompanyInvite invite = findUsableForUpdate(request.token());
        if (accounts.existsByEmailIgnoreCase(invite.getEmail())) throw new ConflictException("이미 가입된 이메일입니다.");
        var result = emailVerification.consume(invite.getEmail(), request.code());
        if (result != EmailVerificationService.ConsumeResult.CONSUMED) {
            throw new IllegalArgumentException(result.message());
        }
        var account = corporateAccounts.signupProctor(invite.getEmail(), request.name(), request.password(), invite.getCompanyId());
        invite.accept(account.getId(), Instant.now());
        return auth.login(new com.pair.domain.auth.dto.LoginRequest(invite.getEmail(), request.password()));
    }

    private CompanyInvite findUsable(String rawToken) {
        if (!InviteTokenService.looksValid(rawToken)) throw new UnauthorizedException("유효하지 않은 기업 초대입니다.");
        CompanyInvite invite = invites.findByTokenHash(InviteTokenService.hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 기업 초대입니다."));
        if (!invite.isUsable(Instant.now())) throw new UnauthorizedException("만료되었거나 이미 사용된 기업 초대입니다.");
        return invite;
    }

    private CompanyInvite findUsableForUpdate(String rawToken) {
        if (!InviteTokenService.looksValid(rawToken)) throw new UnauthorizedException("유효하지 않은 기업 초대입니다.");
        CompanyInvite invite = invites.lockByTokenHash(InviteTokenService.hash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 기업 초대입니다."));
        if (!invite.isUsable(Instant.now())) throw new UnauthorizedException("만료되었거나 이미 사용된 기업 초대입니다.");
        return invite;
    }

    private String inviteUrl(String token) {
        return inviteBaseUrl + (inviteBaseUrl.contains("?") ? "&" : "?") + "token=" + token;
    }
}
