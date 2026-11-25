package com.pair.domain.company.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.security.InviteTokenService;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;
import com.pair.domain.auth.service.EmailVerificationService;
import com.pair.domain.auth.service.WithdrawnEmailHashService;
import com.pair.domain.company.dto.CredentialsIssuedDto;
import com.pair.domain.company.dto.InquiryMemberDto;
import com.pair.domain.auth.repository.RefreshSessionRepository;

@Service
public class CorporateAccountService {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final AuthAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final EmailVerificationService emailVerification;
    private final ApplicationEventPublisher events;
    private final WithdrawnEmailHashService withdrawnEmailHasher;

    public CorporateAccountService(AuthAccountRepository accounts, RefreshSessionRepository sessions,
            EmailVerificationService emailVerification, ApplicationEventPublisher events,
            WithdrawnEmailHashService withdrawnEmailHasher) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.emailVerification = emailVerification;
        this.events = events;
        this.withdrawnEmailHasher = withdrawnEmailHasher;
    }

    @Transactional
    public AuthAccount create(String email, String name, CurrentUser.Role role, Long companyId) {
        return createAll(List.of(new InquiryMemberDto(name, email, role)), companyId).get(0);
    }

    /** 로그인할 수 없는 상태로 계정을 만든다. 임시 비밀번호와 메일은 커밋된 뒤 CorporateCredentialService 가 처리한다. */
    @Transactional
    public List<AuthAccount> createAll(List<InquiryMemberDto> members, Long companyId) {
        List<String> emails = members.stream().map(m -> normalize(m.email())).toList();
        List<String> registered = accounts.findRegisteredEmails(emails);
        if (!registered.isEmpty()) throw new ConflictException("이미 가입된 이메일입니다: " + registered.get(0));
        emailVerification.removeForEmails(emails);

        Map<String, AuthAccount> withdrawn = accounts.lockByEmailIn(
                emails.stream().map(withdrawnEmailHasher::hash).toList()).stream()
                .collect(Collectors.toMap(AuthAccount::getEmail, Function.identity()));
        String placeholder = newInvalidPasswordHash();
        List<AuthAccount> created = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            InquiryMemberDto member = members.get(i);
            AuthAccount account = newOrReactivated(withdrawn.get(withdrawnEmailHasher.hash(emails.get(i))),
                    emails.get(i), member.name().trim(), placeholder);
            account.startCompanyOnboarding(member.role(), companyId);
            created.add(account);
        }
        try {
            created = accounts.saveAllAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일이 있습니다. 다시 확인해 주세요.");
        }
        events.publishEvent(new CredentialsIssuedDto(created.stream().map(AuthAccount::getId).toList(), placeholder));
        return created;
    }

    @Transactional
    public AuthAccount signupProctor(String email, String name, String password, Long companyId) {
        String normalized = normalize(email);
        if (accounts.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
        AuthAccount withdrawn = accounts.findByEmailIgnoreCase(withdrawnEmailHasher.hash(normalized)).orElse(null);
        AuthAccount account = newOrReactivated(withdrawn, normalized, name.trim(), hashSignupPassword(password));
        account.activateAsCompanyMember(CurrentUser.Role.PROCTOR, companyId);
        try {
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 가입된 이메일입니다: " + normalized);
        }
    }

    @Transactional
    public AuthAccount reissue(Long userId) {
        AuthAccount account = accounts.lockById(userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("회원을 찾을 수 없습니다."));
        if (!account.isActive() || account.getCompanyId() == null
                || (account.getRole() != CurrentUser.Role.MANAGER && account.getRole() != CurrentUser.Role.PROCTOR)
                || (!account.isPasswordChangeRequired() && account.isEmailVerified())) {
            throw new IllegalStateException("초기 인증이 끝난 기업 계정은 재발급할 수 없습니다.");
        }
        String placeholder = newInvalidPasswordHash();
        account.resetOnboarding(placeholder);
        sessions.revokeAll(userId);
        emailVerification.removeForEmail(account.getEmail());
        events.publishEvent(new CredentialsIssuedDto(List.of(userId), placeholder));
        return account;
    }

    /** 탈퇴한 회원이면 기존 계정을 되살린다. 새로 만들면 다음 탈퇴 때 탈퇴 이메일 해시가 겹친다. */
    private static AuthAccount newOrReactivated(AuthAccount withdrawn, String email, String name, String passwordHash) {
        if (withdrawn == null) return new AuthAccount(email, name, passwordHash);
        withdrawn.reactivate(email, name, passwordHash);
        return withdrawn;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String hashSignupPassword(String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        return PASSWORD_ENCODER.encode(password);
    }

    public static String newInvalidPasswordHash() {
        return PASSWORD_ENCODER.encode(InviteTokenService.next());
    }
}
