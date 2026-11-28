package com.pair.domain.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.pair.domain.attempt.entity.AttemptReport;
import com.pair.domain.attempt.repository.AttemptReportRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.dto.LoginRequest;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.stats.dto.AdminStatsResponse;
import com.pair.domain.stats.service.AdminStatsService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;
import com.pair.global.security.CurrentUser;
import com.pair.infra.ai.AssistantClient;

/**
 * 관리자 통계: 권한·기간 검사와, 회원 가입·자가진단 응시·채점·AI 채팅이 통계 숫자에 반영되는지.
 * 테스트들이 같은 H2 를 쓰므로 전체 숫자가 아니라 "내가 만든 만큼 늘었는지"(호출 전후 차이)로 본다.
 */
class AdminStatsIntegrationTest extends BearerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AuthAccountRepository accounts;
    @Autowired AttemptService attempts;
    @Autowired AttemptReportRepository reports;
    @Autowired TaskRepository tasks;
    @Autowired AdminStatsService stats;

    @BeforeEach
    void seed() {
        TestTasks.seed(tasks);
        when(assistant.send(any())).thenReturn(new AssistantClient.Reply("answer", "stats-test-model", 10, 20, 0, "stop", 1));
    }

    @Test
    void onlyAdminsCanSeeStats() throws Exception {
        AuthResponse user = signup();
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isForbidden());

        String admin = adminToken();
        mvc.perform(get("/api/admin/stats").param("from", "2026-11-01").param("to", "2026-11-15")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-01"))
                .andExpect(jsonPath("$.to").value("2026-11-15"))
                .andExpect(jsonPath("$.selfDiagnosis.bands.length()").value(4))
                .andExpect(jsonPath("$.selfDiagnosis.bands[3].range").value("80-100"))
                .andExpect(jsonPath("$.ai.taskGen.jobs").isNumber())
                .andExpect(jsonPath("$.credits.paidKrw").isNumber());
    }

    @Test
    void periodMustBeOrderedAndAtMostAYear() throws Exception {
        String admin = adminToken();
        mvc.perform(get("/api/admin/stats").param("from", "2026-11-10").param("to", "2026-11-01")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("시작일이 종료일보다 늦습니다."));
        mvc.perform(get("/api/admin/stats").param("from", "2025-01-01").param("to", "2026-11-01")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/stats").param("from", "2026-11-31").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void newMemberAttemptScoreAndChatTokensAreCounted() {
        AdminStatsResponse before = stats.stats(null, null);

        AuthResponse user = signup();
        String attemptId = attempts.startSelf(TestTasks.SELF_DEV, user.user().id()).id();
        attempts.chat(attemptId, "질문", List.of(), null);
        reports.saveAndFlush(new AttemptReport(Long.parseLong(attemptId), "{\"version\":2,\"total\":85,\"max\":100,\"items\":[]}", Instant.now()));

        AdminStatsResponse after = stats.stats(null, null);

        assertThat(after.members().users() - before.members().users()).isEqualTo(1);
        assertThat(after.members().newUsers() - before.members().newUsers()).isEqualTo(1);
        assertThat(after.selfDiagnosis().started() - before.selfDiagnosis().started()).isEqualTo(1);
        assertThat(after.selfDiagnosis().scored() - before.selfDiagnosis().scored()).isEqualTo(1);
        assertThat(after.selfDiagnosis().averageScore()).isNotNull();
        assertThat(after.selfDiagnosis().bands().get(3).count() - before.selfDiagnosis().bands().get(3).count()).isEqualTo(1);
        assertThat(after.ai().selfChat().calls() - before.ai().selfChat().calls()).isEqualTo(1);
        assertThat(after.ai().selfChat().inputTokens() - before.ai().selfChat().inputTokens()).isEqualTo(10);
        assertThat(after.ai().selfChat().outputTokens() - before.ai().selfChat().outputTokens()).isEqualTo(20);
        assertThat(after.ai().unpricedModels()).contains("stats-test-model");
    }

    @Test
    void adminAttemptsAreNotCountedAsUsage() {
        String adminEmail = "stats-admin-" + UUID.randomUUID() + "@example.com";
        AuthAccount admin = new AuthAccount(adminEmail, "Admin", "unused");
        admin.changeRole(CurrentUser.Role.ADMIN);
        long adminId = accounts.saveAndFlush(admin).getId();
        AdminStatsResponse before = stats.stats(null, null);

        attempts.startSelf(TestTasks.SELF_DEV, adminId);

        assertThat(stats.stats(null, null).selfDiagnosis().started()).isEqualTo(before.selfDiagnosis().started());
    }

    private AuthResponse signup() {
        return AuthTestSupport.signup(auth, mail, "Stats", "stats-" + UUID.randomUUID() + "@example.com");
    }

    /** 일반 회원으로 가입한 뒤 관리자로 바꾸고 다시 로그인한다 (역할을 바꾸면 이전 토큰은 무효) */
    private String adminToken() {
        AuthResponse signed = signup();
        AuthAccount account = accounts.findById(signed.user().id()).orElseThrow();
        account.changeRole(CurrentUser.Role.ADMIN);
        accounts.saveAndFlush(account);
        return auth.login(new LoginRequest(account.getEmail(), "password123")).token();
    }
}
