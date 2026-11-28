package com.pair.domain.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.company.entity.CompanyInvite;
import com.pair.domain.company.repository.CompanyInviteRepository;
import com.pair.domain.company.repository.CompanyRepository;
import com.pair.domain.company.service.CompanyDeletionService;
import com.pair.domain.company.service.CompanyInquiryService;
import com.pair.domain.company.service.CompanyInviteService;
import com.pair.domain.company.service.CompanyMemberService;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.service.ConsoleAccessService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;

/**
 * 관리자의 회사 삭제(소프트 삭제) 시나리오:
 * A 회사 매니저가 등록 취소를 요청 → 관리자가 A 를 삭제 → 매니저는 탈퇴 처리 → 다른 회사(B)의 도입 문의 명단에 감독관으로 들어와
 * 같은 계정 행이 B 의 감독관으로 되살아난다.
 */
class CompanyDeletionIntegrationTest extends BearerIntegrationTest {

    @Autowired CompanyDeletionService deletion;
    @Autowired CompanyInquiryService inquiries;
    @Autowired CompanyInviteService invites;
    @Autowired CompanyMemberService members;
    @Autowired CompanyRepository companies;
    @Autowired CompanyInviteRepository inviteRows;
    @Autowired AuthAccountRepository accounts;
    @Autowired ExamSessionRepository sessions;
    @Autowired ConsoleAccessService access;
    @Autowired TaskRepository tasks;

    /** 회차는 과제가 하나 이상 있어야 한다 */
    private static final List<Long> TASKS = List.of(3L);

    @BeforeEach
    void seed() {
        TestTasks.seed(tasks);
        when(mail.createMimeMessage()).thenAnswer(inv -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
    }

    @Test
    void deletedCompanyManagerCanJoinAnotherCompanyAsProctorWithTheSameAccount() {
        String s = suffix();
        CurrentUser admin = admin(s);
        String managerEmail = "manager-" + s + "@test.dev";
        String proctorEmail = "proctor-" + s + "@test.dev";
        long amigo = approve("아미고테크 " + s, managerEmail + ",MANAGER\n" + proctorEmail + ",PROCTOR", admin);
        AuthAccount manager = accountByEmail(managerEmail);
        Long managerId = manager.getId();

        Instant now = Instant.now();
        ExamSession upcoming = sessions.save(new ExamSession(amigo, "예정 회차", now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(3)).plus(Duration.ofHours(2)), false, TASKS, managerId, now));
        ExamSession ended = sessions.save(new ExamSession(amigo, "끝난 회차", now.minus(Duration.ofDays(3)),
                now.minus(Duration.ofDays(3)).plus(Duration.ofHours(2)), false, TASKS, managerId, now.minus(Duration.ofDays(4))));
        invites.inviteProctor(new CurrentUser(managerId, CurrentUser.Role.MANAGER, amigo), "invited-" + s + "@test.dev");

        var result = deletion.delete(amigo, admin);

        assertThat(result.withdrawnMembers()).isEqualTo(2);
        assertThat(result.canceledSessions()).isEqualTo(1);
        assertThat(result.revokedInvites()).isEqualTo(1);
        // 회사: 목록·상세에서 사라지고, 관리자가 회차를 새로 열 수도 없다
        assertThat(companies.findById(amigo).orElseThrow().isDeleted()).isTrue();
        assertThat(members.allCompanies()).noneMatch(c -> c.id() == amigo);
        assertThatThrownBy(() -> members.company(amigo)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> access.companyFor(admin, amigo)).isInstanceOf(NoSuchElementException.class);
        // 회차: 예정은 취소, 끝난 회차는 그대로(기록 보존)
        assertThat(sessions.findById(upcoming.getId()).orElseThrow().getCanceledAt()).isNotNull();
        assertThat(sessions.findById(ended.getId()).orElseThrow().getCanceledAt()).isNull();
        // 초대: 무효
        assertThat(inviteRows.findAll()).filteredOn(i -> i.getCompanyId().equals(amigo))
                .allMatch(i -> !i.isUsable(Instant.now()));
        // 멤버: 탈퇴 처리되어 이메일이 비고, 같은 이메일로 다시 들어올 수 있다
        AuthAccount withdrawn = accounts.findById(managerId).orElseThrow();
        assertThat(withdrawn.getMemberStatus()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.getEmail()).startsWith("withdrawn:");
        assertThat(accounts.existsByEmailIgnoreCase(managerEmail)).isFalse();
        assertThatThrownBy(() -> deletion.delete(amigo, admin)).isInstanceOf(ConflictException.class);

        // 이직한 회사의 도입 문의 명단에 옛 매니저가 감독관으로 들어온다
        long next = approve("다음회사 " + s, "boss-" + s + "@test.dev,MANAGER\n" + managerEmail + ",PROCTOR", admin);

        AuthAccount rejoined = accounts.findById(managerId).orElseThrow();
        assertThat(rejoined.getEmail()).isEqualTo(managerEmail);
        assertThat(rejoined.getRole()).isEqualTo(CurrentUser.Role.PROCTOR);
        assertThat(rejoined.getCompanyId()).isEqualTo(next);
        assertThat(rejoined.getMemberStatus()).isNotEqualTo("WITHDRAWN");
    }

    @Test
    void companyWithLiveSessionCannotBeDeleted() {
        String s = suffix();
        CurrentUser admin = admin(s);
        String managerEmail = "live-manager-" + s + "@test.dev";
        long company = approve("진행회사 " + s, managerEmail + ",MANAGER", admin);
        Instant now = Instant.now();
        sessions.save(new ExamSession(company, "진행 중 회차", now.minus(Duration.ofMinutes(10)),
                now.plus(Duration.ofHours(1)), false, TASKS, accountByEmail(managerEmail).getId(), now.minus(Duration.ofDays(1))));

        assertThatThrownBy(() -> deletion.delete(company, admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("진행 중인 회차");
        assertThat(companies.findById(company).orElseThrow().isDeleted()).isFalse();
        assertThat(accountByEmail(managerEmail).getMemberStatus()).isNotEqualTo("WITHDRAWN");
    }

    @Test
    void invitesOfADeletedCompanyCannotBeOpened() {
        String s = suffix();
        CurrentUser admin = admin(s);
        String managerEmail = "invite-manager-" + s + "@test.dev";
        long company = approve("초대회사 " + s, managerEmail + ",MANAGER", admin);
        invites.inviteProctor(new CurrentUser(accountByEmail(managerEmail).getId(), CurrentUser.Role.MANAGER, company),
                "late-" + s + "@test.dev");
        CompanyInvite invite = inviteRows.findAll().stream()
                .filter(i -> i.getEmail().equals("late-" + s + "@test.dev")).findFirst().orElseThrow();

        deletion.delete(company, admin);

        assertThat(inviteRows.findById(invite.getId()).orElseThrow().isUsable(Instant.now())).isFalse();
    }

    private long approve(String companyName, String roster, CurrentUser admin) {
        var inquiry = inquiries.submit(companyName, "사업자" + suffix(), "담당자", "contact-" + suffix() + "@test.dev",
                null, csv(roster));
        return inquiries.approve(inquiry.id(), admin.userId()).id();
    }

    private AuthAccount accountByEmail(String email) {
        // findByEmailIgnoreCase 는 잠금 조회라 트랜잭션 밖인 테스트에서는 쓰지 않는다
        return accounts.findAll().stream().filter(a -> a.getEmail().equalsIgnoreCase(email)).findFirst().orElseThrow();
    }

    private CurrentUser admin(String s) {
        AuthAccount account = new AuthAccount("admin-" + s + "@test.dev", "Admin",
                new BCryptPasswordEncoder().encode("admin-password"));
        account.changeRole(CurrentUser.Role.ADMIN);
        return new CurrentUser(accounts.saveAndFlush(account).getId(), CurrentUser.Role.ADMIN, null);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] csv(String entries) {
        String rows = java.util.Arrays.stream(entries.split("\\n"))
                .map(row -> "사용자," + row)
                .collect(java.util.stream.Collectors.joining("\r\n"));
        return ("﻿회원명,회원이메일,역할\r\n" + rows + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
