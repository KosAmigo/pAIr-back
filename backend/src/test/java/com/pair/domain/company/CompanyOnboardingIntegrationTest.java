package com.pair.domain.company;

import org.junit.jupiter.api.Tag;

import com.pair.domain.company.dto.InviteAcceptRequest;
import com.pair.domain.company.dto.ReplaceManagerRequest;
import com.pair.domain.company.service.CompanyAccountMailService;
import com.pair.domain.company.service.CompanyInquiryService;
import com.pair.domain.company.service.CompanyInviteService;
import com.pair.domain.company.service.CompanyMemberService;
import com.pair.domain.company.service.CorporateAccountService;
import com.pair.domain.company.service.CorporateCredentialService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.LoginRequest;
import com.pair.domain.auth.dto.WithdrawalRequest;
import com.pair.global.error.UnauthorizedException;
import com.pair.domain.auth.exception.EmailVerificationException;
import com.pair.infra.mail.MailDeliveryException;
import org.springframework.mail.MailSendException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.global.security.CurrentUser;

@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
@Tag("slow")
class CompanyOnboardingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CompanyInquiryService inquiries;
    @Autowired CompanyInviteService invites;
    @Autowired CompanyMemberService members;
    @Autowired CorporateAccountService corporateAccounts;
    @Autowired CorporateCredentialService credentials;
    @Autowired AuthAccountRepository accounts;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CompanyAccountMailService mailer;
    @MockitoBean JavaMailSender mail;

    @Test
    void inquiryApprovalSupportsManagerOnlyAndEnforcesManagerCount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                "문의", csv("manager-" + suffix + "@test.dev,MANAGER"));

        assertThat(inquiry.members()).hasSize(1);
        var approved = inquiries.approve(inquiry.id(), admin.getId());
        AuthAccount manager = account("manager-" + suffix + "@test.dev");
        assertThat(manager.getRole()).isEqualTo(CurrentUser.Role.MANAGER);
        assertThat(manager.getCompanyId()).isEqualTo(approved.id());
        assertThat(manager.isEmailVerified()).isFalse();
        assertThat(manager.isPasswordChangeRequired()).isTrue();
        assertThat(manager.getOnboardingMailStatus()).isEqualTo("SENT");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inquiries.submit("회사", "사업자", "담당자",
                "bad-" + suffix + "@test.dev", null, csv("proctor-" + suffix + "@test.dev,PROCTOR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGER가 정확히 1명");
    }

    @Test
    void proctorInviteVerifiesEmailAndCreatesAccountInSameCompany() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER"));
        Long companyId = inquiries.approve(inquiry.id(), admin.getId()).id();
        AuthAccount manager = account("manager-" + suffix + "@test.dev");
        String proctorEmail = "new-proctor-" + suffix + "@test.dev";

        invites.inviteProctor(new CurrentUser(manager.getId(), CurrentUser.Role.MANAGER, companyId), proctorEmail);
        ArgumentCaptor<String> invitationUrl = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendProctorInvite(org.mockito.ArgumentMatchers.eq(proctorEmail),
                org.mockito.ArgumentMatchers.anyString(), invitationUrl.capture());
        String token = invitationUrl.getValue().substring(invitationUrl.getValue().indexOf("token=") + 6);
        assertThat(invites.inspect(token).role()).isEqualTo("PROCTOR");

        when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(ignored -> null).when(mail).send(any(MimeMessage.class));
        invites.requestSignupCode(token);
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(message.capture());
        String code = verificationCode(message.getValue());

        String wrong = "000000".equals(code) ? "000001" : "000000";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invites.accept(
                new InviteAcceptRequest(token, "감독관", "new-password-123", wrong)))
                .isInstanceOf(EmailVerificationException.class);
        assertThat(failedAttempts(proctorEmail)).isEqualTo(1);
        // 코드는 맞지만 비밀번호가 72바이트를 넘어 실패하면, 코드를 다시 쓸 수 있어야 한다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invites.accept(
                new InviteAcceptRequest(token, "감독관", "가".repeat(25), code)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72바이트");

        var session = invites.accept(new InviteAcceptRequest(token, "감독관", "new-password-123", code));
        assertThat(session.user().role()).isEqualTo(CurrentUser.Role.PROCTOR);
        assertThat(session.user().companyId()).isEqualTo(companyId);
        assertThat(session.user().emailVerified()).isTrue();
        assertThat(session.user().passwordChangeRequired()).isFalse();
    }

    @Test
    void proctorCanBeInvitedAgainAndOldLinkStopsWorking() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount manager = approvedManager(suffix);
        // 운영(V21)의 부분 유니크 인덱스를 H2 에서 흉내 낸다: 미사용 초대일 때만 이메일이 들어가는 계산 열
        jdbc.execute("alter table company_invites add column pending_email varchar(255) generated always as "
                + "(case when accepted_at is null and revoked_at is null then lower(email) end)");
        try {
            jdbc.execute("create unique index uq_test_pending_invite on company_invites (pending_email)");
            CurrentUser user = new CurrentUser(manager.getId(), CurrentUser.Role.MANAGER, manager.getCompanyId());
            String email = "again-" + suffix + "@test.dev";
            invites.inviteProctor(user, email);
            invites.inviteProctor(user, email);

            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            verify(mailer, times(2)).sendProctorInvite(eq(email), anyString(), urls.capture());
            assertThatThrownBy(() -> invites.inspect(token(urls.getAllValues().get(0))))
                    .isInstanceOf(UnauthorizedException.class);
            assertThat(invites.inspect(token(urls.getAllValues().get(1))).email()).isEqualTo(email);
        } finally {
            jdbc.execute("drop index if exists uq_test_pending_invite");
            jdbc.execute("alter table company_invites drop column pending_email");
        }
    }

    @Test
    void failedInviteMailRevokesInviteSoManagerCanInviteAgain() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount manager = approvedManager(suffix);
        CurrentUser user = new CurrentUser(manager.getId(), CurrentUser.Role.MANAGER, manager.getCompanyId());
        String email = "retry-" + suffix + "@test.dev";
        doThrow(new MailDeliveryException(new MailSendException("smtp down")))
                .doNothing().when(mailer).sendProctorInvite(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> invites.inviteProctor(user, email)).isInstanceOf(MailDeliveryException.class);
        assertThat(jdbc.queryForObject("select count(*) from company_invites where lower(email) = ? and revoked_at is null",
                Integer.class, email)).isZero();
        invites.inviteProctor(user, email);
        assertThat(jdbc.queryForObject("select count(*) from company_invites where lower(email) = ? and revoked_at is null",
                Integer.class, email)).isEqualTo(1);
    }

    @Test
    void credentialMailFailureKeepsApprovalAndMarksAccountFailed() {
        doThrow(new MailDeliveryException(new MailSendException("smtp down")))
                .when(mailer).sendCredentials(any(AuthAccount.class), anyString());
        AuthAccount manager = approvedManager(UUID.randomUUID().toString().substring(0, 8));
        assertThat(manager.getCompanyId()).isNotNull();
        assertThat(manager.getOnboardingMailStatus()).isEqualTo("FAILED");
    }

    @Test
    void approvalDoesNotWaitForMailAndContinuesAfterOneFailure() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String managerEmail = "manager-" + suffix + "@test.dev";
        String proctorEmail = "proctor-" + suffix + "@test.dev";
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            AuthAccount target = inv.getArgument(0);
            if (!target.getEmail().equals(managerEmail)) return null;
            sending.countDown();
            release.await(10, TimeUnit.SECONDS);
            throw new MailDeliveryException(new MailSendException("smtp down"));
        }).when(mailer).sendCredentials(any(AuthAccount.class), anyString());
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv(managerEmail + ",MANAGER\n" + proctorEmail + ",PROCTOR"));
        try {
            inquiries.approve(inquiry.id(), admin(suffix).getId());
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(accountNow(managerEmail).getOnboardingMailStatus()).isEqualTo("PENDING");
        } finally {
            release.countDown();
        }
        assertThat(account(managerEmail).getOnboardingMailStatus()).isEqualTo("FAILED");
        assertThat(account(proctorEmail).getOnboardingMailStatus()).isEqualTo("SENT");
    }

    @Test
    void rejectedApprovalSendsNoMail() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER\n" + admin.getEmail().toUpperCase() + ",PROCTOR"));
        assertThatThrownBy(() -> inquiries.approve(inquiry.id(), admin.getId()))
                .isInstanceOf(com.pair.global.error.ConflictException.class).hasMessageContaining(admin.getEmail());
        assertThat(accounts.existsByEmailIgnoreCase("manager-" + suffix + "@test.dev")).isFalse();
        verify(mailer, never()).sendCredentials(any(AuthAccount.class), anyString());
    }

    @Test
    void reissueDuringMailIsNotOverwrittenByOlderIssue() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (!first.getAndSet(false)) return null;
            // 첫 메일을 보내는 사이 재발급됐고, 첫 메일은 실패했다
            members.reissue(inv.<AuthAccount>getArgument(0).getId(),
                    new CurrentUser(admin.getId(), CurrentUser.Role.ADMIN, null));
            throw new MailDeliveryException(new MailSendException("smtp down"));
        }).when(mailer).sendCredentials(any(AuthAccount.class), anyString());
        AuthAccount manager = approvedManager(suffix, admin);
        assertThat(manager.getOnboardingMailStatus()).isEqualTo("SENT");
        ArgumentCaptor<String> passwords = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(2)).sendCredentials(any(AuthAccount.class), passwords.capture());
        assertThatThrownBy(() -> auth.login(new LoginRequest(manager.getEmail(), passwords.getAllValues().get(0))))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(auth.login(new LoginRequest(manager.getEmail(), passwords.getAllValues().get(1))).token()).isNotBlank();

        credentials.issue(manager.getId(), "outdated-placeholder");
        assertThat(accounts.findById(manager.getId()).orElseThrow().getPasswordHash()).isEqualTo(manager.getPasswordHash());
        verify(mailer, times(2)).sendCredentials(any(AuthAccount.class), anyString());
    }

    @Test
    void withdrawnMemberRejoinsAsProctorWithSameAccountAndCanWithdrawAgain() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "rejoin-" + suffix + "@test.dev";
        var first = AuthTestSupport.signup(auth, mail, "회원", email);
        auth.withdraw(first.token(), new WithdrawalRequest("password123"));
        AuthAccount manager = approvedManager(suffix);

        AuthAccount rejoined = corporateAccounts.signupProctor(email, "감독관", "password123", manager.getCompanyId());
        assertThat(rejoined.getId()).isEqualTo(first.user().id());
        assertThat(rejoined.getRole()).isEqualTo(CurrentUser.Role.PROCTOR);
        auth.withdraw(auth.login(new LoginRequest(email, "password123")).token(), new WithdrawalRequest("password123"));
        assertThat(accounts.findById(rejoined.getId()).orElseThrow().getMemberStatus()).isEqualTo("WITHDRAWN");

        AuthAccount created = corporateAccounts.create(email, "감독관", CurrentUser.Role.PROCTOR, manager.getCompanyId());
        assertThat(created.getId()).isEqualTo(first.user().id());
        assertThat(created.isActive()).isTrue();
        assertThat(created.isEmailVerified()).isFalse();
        assertThat(created.isPasswordChangeRequired()).isTrue();
        mailed(created.getId());
    }

    @Test
    void accountDuplicatedByOldRejoinCanStillWithdraw() {
        String email = "duplicate-" + UUID.randomUUID() + "@test.dev";
        var first = AuthTestSupport.signup(auth, mail, "회원", email);
        auth.withdraw(first.token(), new WithdrawalRequest("password123"));
        AuthAccount duplicate = accounts.saveAndFlush(new AuthAccount(email, "감독관",
                new BCryptPasswordEncoder().encode("password123")));
        auth.withdraw(auth.login(new LoginRequest(email, "password123")).token(), new WithdrawalRequest("password123"));
        assertThat(accounts.findById(duplicate.getId()).orElseThrow().getMemberStatus()).isEqualTo("WITHDRAWN");
    }

    @Test
    void onboardingRequiresVerificationAndPasswordChangeForProtectedApis() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER"));
        inquiries.approve(inquiry.id(), admin.getId());
        AuthAccount manager = account("manager-" + suffix + "@test.dev");
        ArgumentCaptor<String> temporaryPassword = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendCredentials(org.mockito.ArgumentMatchers.any(AuthAccount.class),
                temporaryPassword.capture());
        var login = auth.login(new com.pair.domain.auth.dto.LoginRequest(manager.getEmail(), temporaryPassword.getValue()));

        mvc.perform(get("/api/console/sessions").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk());

        when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(ignored -> null).when(mail).send(any(MimeMessage.class));
        auth.requestOnboardingEmailVerification(login.token());
        ArgumentCaptor<MimeMessage> verificationMail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(verificationMail.capture());
        String code = verificationCode(verificationMail.getValue());
        assertThat(jdbc.queryForObject("select expires_at from email_verifications where email = ?",
                java.time.Instant.class, manager.getEmail())).isNull();
        assertThat(accounts.findById(manager.getId()).orElseThrow()).isNotNull();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.verifyOnboardingEmail(login.token(),
                "000000".equals(code) ? "000001" : "000000")).isInstanceOf(EmailVerificationException.class);
        assertThat(failedAttempts(manager.getEmail())).isEqualTo(1);
        auth.verifyOnboardingEmail(login.token(), code);
        assertThat(accounts.findById(manager.getId()).orElseThrow().isEmailVerified()).isTrue();
        // 메일로 받은 임시 비밀번호를 그대로 새 비밀번호로 쓸 수 없고, 변경 의무도 풀리지 않는다
        mvc.perform(patch("/api/auth/password").header("Authorization", "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporaryPassword.getValue()
                                + "\",\"newPassword\":\"" + temporaryPassword.getValue() + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(accounts.findById(manager.getId()).orElseThrow().isPasswordChangeRequired()).isTrue();
        mvc.perform(patch("/api/auth/password").header("Authorization", "Bearer " + login.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporaryPassword.getValue()
                                + "\",\"newPassword\":\"changed-password-123\"}"))
                .andExpect(status().isNoContent());
        var completed = auth.login(new com.pair.domain.auth.dto.LoginRequest(manager.getEmail(), "changed-password-123"));
        assertThat(completed.user().passwordChangeRequired()).isFalse();
        mvc.perform(get("/api/console/sessions").header("Authorization", "Bearer " + completed.token()))
                .andExpect(status().isOk());
    }

    @Test
    void managerDelegationPromotesSelectedProctorAndDemotesCurrentManager() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER\nproctor-" + suffix + "@test.dev,PROCTOR"));
        Long companyId = inquiries.approve(inquiry.id(), admin.getId()).id();
        AuthAccount manager = account("manager-" + suffix + "@test.dev");
        AuthAccount proctor = account("proctor-" + suffix + "@test.dev");
        proctor.markEmailVerified();
        proctor.changePassword(new BCryptPasswordEncoder().encode("completed-password"));
        accounts.saveAndFlush(proctor);

        members.delegate(new CurrentUser(manager.getId(), CurrentUser.Role.MANAGER, companyId), proctor.getId());

        assertThat(accounts.findById(manager.getId()).orElseThrow().getRole()).isEqualTo(CurrentUser.Role.PROCTOR);
        assertThat(accounts.findById(proctor.getId()).orElseThrow().getRole()).isEqualTo(CurrentUser.Role.MANAGER);
        assertThat(members.company(companyId).members()).filteredOn(m -> m.status().equals("ACTIVE"))
                .filteredOn(m -> m.role() == CurrentUser.Role.MANAGER).hasSize(1);
    }

    @Test
    void adminReplacesManagerAndCanReissueTemporaryCredentials() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthAccount admin = admin(suffix);
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER"));
        Long companyId = inquiries.approve(inquiry.id(), admin.getId()).id();
        AuthAccount oldManager = account("manager-" + suffix + "@test.dev");
        long oldTokenVersion = oldManager.getTokenVersion();

        var replaced = members.replaceManager(companyId, new CurrentUser(admin.getId(), CurrentUser.Role.ADMIN, null),
                new ReplaceManagerRequest("새 매니저", "replacement-" + suffix + "@test.dev"));

        AuthAccount withdrawn = accounts.findById(oldManager.getId()).orElseThrow();
        AuthAccount newManager = mailed(replaced.managerUserId());
        assertThat(withdrawn.getMemberStatus()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.getTokenVersion()).isGreaterThan(oldTokenVersion);
        assertThat(newManager.getRole()).isEqualTo(CurrentUser.Role.MANAGER);
        assertThat(newManager.isPasswordChangeRequired()).isTrue();

        members.reissue(newManager.getId(), new CurrentUser(admin.getId(), CurrentUser.Role.ADMIN, null));
        AuthAccount reissued = mailed(newManager.getId());
        assertThat(reissued.isEmailVerified()).isFalse();
        assertThat(reissued.isPasswordChangeRequired()).isTrue();
        assertThat(reissued.getOnboardingMailStatus()).isEqualTo("SENT");
    }

    private Integer failedAttempts(String email) {
        return jdbc.queryForObject("select failed_attempts from email_verifications where email = ?", Integer.class, email);
    }

    private AuthAccount approvedManager(String suffix) {
        return approvedManager(suffix, admin(suffix));
    }

    private AuthAccount approvedManager(String suffix, AuthAccount admin) {
        var inquiry = inquiries.submit("회사 " + suffix, "사업자" + suffix, "담당자", "contact-" + suffix + "@test.dev",
                null, csv("manager-" + suffix + "@test.dev,MANAGER"));
        inquiries.approve(inquiry.id(), admin.getId());
        return account("manager-" + suffix + "@test.dev");
    }

    private static String token(String url) {
        return url.substring(url.indexOf("token=") + 6);
    }

    private AuthAccount admin(String suffix) {
        AuthAccount account = new AuthAccount("admin-" + suffix + "@test.dev", "Admin",
                new BCryptPasswordEncoder().encode("admin-password"));
        account.changeRole(CurrentUser.Role.ADMIN);
        return accounts.saveAndFlush(account);
    }

    /** 메일은 커밋 뒤 다른 스레드에서 보내므로 처리가 끝날 때까지 기다린다 */
    private AuthAccount account(String email) {
        return mailed(accountNow(email).getId());
    }

    private AuthAccount accountNow(String email) {
        return accounts.findAll().stream().filter(a -> a.getEmail().equalsIgnoreCase(email)).findFirst().orElseThrow();
    }

    private AuthAccount mailed(Long id) {
        return Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> accounts.findById(id).orElseThrow(),
                a -> !a.getOnboardingMailStatus().equals("PENDING"));
    }

    private static byte[] csv(String entries) {
        String rows = java.util.Arrays.stream(entries.split("\\n"))
                .map(row -> "사용자," + row)
                .collect(java.util.stream.Collectors.joining("\r\n"));
        return ("\uFEFF회원명,회원이메일,역할\r\n" + rows + "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String verificationCode(MimeMessage message) throws Exception {
        String text = flatten(message.getContent());
        Matcher matcher = Pattern.compile("인증 코드: ([0-9]{6})").matcher(text);
        if (!matcher.find()) throw new AssertionError("인증 코드가 메일에 없습니다.");
        return matcher.group(1);
    }

    private static String flatten(Object content) throws Exception {
        if (content instanceof String text) return text;
        if (content instanceof MimeMultipart multipart) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) result.append(flatten(multipart.getBodyPart(i).getContent()));
            return result.toString();
        }
        return "";
    }
}
