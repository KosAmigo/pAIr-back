package com.pair.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.exam.service.MailLayout;

/** 가입·로그인·로그아웃과 토큰 기반 권한 확인 흐름을 H2 DB로 검증한다. */
@SpringBootTest(properties = "pair.auth.mode=bearer")
@AutoConfigureMockMvc
@ActiveProfiles("examtest")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthAccountRepository accounts;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender mail;

    @Test
    void signupLoginMeAndLogout() throws Exception {
        String code = requestVerificationCode("Test@Example.com");
        MvcResult signup = mvc.perform(signup("테스트 사용자", "Test@Example.com", "secure-pass-123", code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();
        JsonNode signupBody = json.readTree(signup.getResponse().getContentAsString());
        String signupToken = signupBody.path("token").asText();
        String refreshToken = signupBody.path("refreshToken").asText();
        assertThat(signupBody.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(signupToken.split("\\.")).hasSize(3);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(signupBody.path("user").path("id").asLong()));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"TEST@example.com","password":"secure-pass-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"));

        MvcResult refreshed = mvc.perform(tokenRequest("/api/auth/refresh", refreshToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode refreshedBody = json.readTree(refreshed.getResponse().getContentAsString());
        String newRefresh = refreshedBody.path("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refreshedBody.path("token").asText()))
                .andExpect(status().isOk());
        mvc.perform(tokenRequest("/api/auth/refresh", refreshToken)).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/logout", refreshToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(tokenRequest("/api/auth/logout", newRefresh))
                .andExpect(status().isNoContent());
        mvc.perform(tokenRequest("/api/auth/refresh", newRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signupToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 코드 메일은 시험 초대 메일과 같은 서식(로고 머리띠·표)의 HTML 이고, 글자 본문에도 코드가 있다")
    void verificationMailUsesInviteLayout() throws Exception {
        AuthTestSupport.prepareMail(mail);
        mvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.EmailVerificationRequest("layout@example.com"))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        clearInvocations(mail);

        String html = AuthTestSupport.htmlText(sent.getValue());
        String code = Pattern.compile("인증 코드: ([0-9]{6})").matcher(AuthTestSupport.plainText(sent.getValue()))
                .results().findFirst().orElseThrow().group(1);
        assertThat(html).contains("src=\"cid:" + MailLayout.LOGO_CID + "\"")
                .contains("회원가입 이메일 인증 코드").contains(code).contains("유효 시간")
                .contains("회원가입 인증을 위해 자동으로 발송");
        assertThat(sent.getValue().getSubject()).isEqualTo("[pAIr] 회원가입 이메일 인증 코드");
    }

    @Test
    void rejectsDuplicateSignupAndWrongPassword() throws Exception {
        String code = requestVerificationCode("duplicate@example.com");
        mvc.perform(signup("테스트 사용자", "duplicate@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated());
        mvc.perform(signup("테스트 사용자", "duplicate@example.com", "secure-pass-123", code))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"duplicate@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void consoleIdentityComesFromTokenInsteadOfCallerSuppliedHeaders() throws Exception {
        mvc.perform(get("/api/console/sessions")
                        .header(HeaderCurrentUserResolver.USER_ID, "1")
                        .header(HeaderCurrentUserResolver.ROLE, "ADMIN"))
                .andExpect(status().isUnauthorized());

        String code = requestVerificationCode("token-user@example.com");
        MvcResult signup = mvc.perform(signup("응시자", "token-user@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(signup.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/api/console/sessions")
                        .header("Authorization", "Bearer " + token)
                        .header(HeaderCurrentUserResolver.USER_ID, "1")
                        .header(HeaderCurrentUserResolver.ROLE, "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signupRequiresValidEmailVerificationCode() throws Exception {
        mvc.perform(signup("미인증 사용자", "unverified@example.com", "secure-pass-123", "123456"))
                .andExpect(status().isBadRequest());

        String code = requestVerificationCode("verified@example.com");
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", "000000".equals(code) ? "000001" : "000000"))
                .andExpect(status().isBadRequest());
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated());
        mvc.perform(signup("인증 사용자", "verified@example.com", "secure-pass-123", code))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsExpiredTamperedWrongTypeAndLegacyTokens() throws Exception {
        String code = requestVerificationCode("jwt-check@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "jwt-check@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        long userId = body.path("user").path("id").asLong();
        String access = body.path("token").asText();
        String expiredAccess = tokens.access(userId, 0, Instant.now().minus(JwtTokens.ACCESS_LIFETIME).minusSeconds(1));
        String expiredRefresh = tokens.refresh(userId, com.pair.exam.service.InviteTokens.next(),
                Instant.now().minus(JwtTokens.REFRESH_LIFETIME).minusSeconds(1));
        String[] parts = access.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        for (String invalid : new String[] { expiredAccess, tampered, com.pair.exam.service.InviteTokens.next() }) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(tokenRequest("/api/auth/refresh", expiredRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/refresh", access)).andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveAccountCannotUseOrRefreshExistingTokens() throws Exception {
        String code = requestVerificationCode("inactive-jwt@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "inactive-jwt@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        jdbc.update("update users set member_status = ? where user_id = ?", "WITHDRAWN", body.path("user").path("id").asLong());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + body.path("token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/refresh", body.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOneConcurrentRefreshCanConsumeTheSameToken() throws Exception {
        String code = requestVerificationCode("concurrent-jwt@example.com");
        MvcResult result = mvc.perform(signup("테스트 사용자", "concurrent-jwt@example.com", "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        String refresh = json.readTree(result.getResponse().getContentAsString()).path("refreshToken").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryRefresh(refresh, start));
            var second = executor.submit(() -> tryRefresh(refresh, start));
            start.countDown();
            assertThat(new boolean[] { first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS) })
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesAllDevices() throws Exception {
        String email = "password-change@example.com";
        JsonNode first = signupAccount(email);
        JsonNode second = login(email, "secure-pass-123");
        long userId = first.path("user").path("id").asLong();

        mvc.perform(passwordChange(first.path("token").asText(), "wrong-password", "new-secure-pass"))
                .andExpect(status().isUnauthorized());
        mvc.perform(passwordChange(first.path("token").asText(), "secure-pass-123", "가".repeat(25)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second.path("token").asText()))
                .andExpect(status().isOk());

        mvc.perform(passwordChange(first.path("token").asText(), "secure-pass-123", "new-secure-pass"))
                .andExpect(status().isNoContent());
        for (JsonNode session : new JsonNode[] { first, second }) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + session.path("token").asText()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(tokenRequest("/api/auth/refresh", session.path("refreshToken").asText()))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_sessions where user_id = ?", Integer.class, userId))
                .isZero();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.Credentials(email, "secure-pass-123"))))
                .andExpect(status().isUnauthorized());
        JsonNode renewed = login(email, "new-secure-pass");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + renewed.path("token").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void withdrawalAndRejoinRestoreAccountWithoutAnotherSignupBonus() throws Exception {
        String email = "withdraw@example.com";
        JsonNode first = signupAccount(email);
        JsonNode second = login(email, "secure-pass-123");
        long userId = first.path("user").path("id").asLong();
        String token = first.path("token").asText();
        jdbc.update("update users set role = 'PROCTOR', company_id = ? where user_id = ?", 41006L, userId);
        jdbc.update("insert into credit_ledger (user_id, amount, balance_after, reason, created_at) values (?, ?, ?, ?, ?)",
                userId, 25, 225, "REFUND", Instant.now());

        mvc.perform(withdrawal(token, "wrong-password")).andExpect(status().isUnauthorized());
        mvc.perform(withdrawal(token, "secure-pass-123")).andExpect(status().isNoContent());
        var row = jdbc.queryForMap("select email, name, password_hash, member_status, deleted_at from users where user_id = ?", userId);
        String hashedEmail = row.get("email").toString();
        assertThat(hashedEmail).matches("withdrawn:[0-9a-f]{64}").doesNotContain(email);
        assertThat(row.get("name")).isEqualTo("UNKOWN");
        assertThat(row.get("member_status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("secure-pass-123", row.get("password_hash").toString())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_verifications where email = ?", Integer.class, email))
                .isZero();
        for (JsonNode session : new JsonNode[] { first, second }) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + session.path("token").asText()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(tokenRequest("/api/auth/refresh", session.path("refreshToken").asText()))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(withdrawal(token, "secure-pass-123")).andExpect(status().isUnauthorized());
        mvc.perform(signup("새 이름", email, "new-secure-pass", "123456")).andExpect(status().isBadRequest());
        String code = requestVerificationCode("WITHDRAW@example.com");
        MvcResult result = mvc.perform(signup("새 이름", "WITHDRAW@example.com", "new-secure-pass", code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("새 이름"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.companyId").doesNotExist())
                .andReturn();
        JsonNode rejoined = json.readTree(result.getResponse().getContentAsString());
        row = jdbc.queryForMap("select email, member_status, deleted_at, role, company_id from users where user_id = ?", userId);
        assertThat(row.get("email")).isEqualTo(email);
        assertThat(row.get("member_status")).isEqualTo("ACTIVE");
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("company_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select balance_after from credit_ledger where user_id = ? order by ledger_id desc limit 1", Integer.class, userId))
                .isEqualTo(225);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.Credentials(email, "secure-pass-123"))))
                .andExpect(status().isUnauthorized());
        login(email, "new-secure-pass");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest("/api/auth/refresh", first.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(withdrawal(rejoined.path("token").asText(), "new-secure-pass")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select email from users where user_id = ?", String.class, userId))
                .isEqualTo(hashedEmail);
        assertThat(signupAccount(email).path("user").path("id").asLong()).isEqualTo(userId);
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id = ? and reason = 'SIGNUP'", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void concurrentRejoinsCannotCreateAnotherAccountOrSignupBonus() throws Exception {
        String email = "concurrent-rejoin@example.com";
        JsonNode first = signupAccount(email);
        long userId = first.path("user").path("id").asLong();
        mvc.perform(withdrawal(first.path("token").asText(), "secure-pass-123")).andExpect(status().isNoContent());
        String code = requestVerificationCode(email);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> trySignup(email, code, start));
            var two = executor.submit(() -> trySignup(email, code, start));
            start.countDown();
            assertThat(new boolean[] { one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS) })
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id = ? and reason = 'SIGNUP'", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void companyRolesCannotWithdrawWhileCompanyHasUpcomingOrLiveSessions() throws Exception {
        for (String role : new String[] { "MANAGER", "PROCTOR" }) {
            JsonNode account = signupAccount(role.toLowerCase() + "-withdraw@example.com");
            long userId = account.path("user").path("id").asLong();
            long companyId = role.equals("MANAGER") ? 41001L : 41002L;
            jdbc.update("update users set role = ?, company_id = ? where user_id = ?", role, companyId, userId);
            Instant now = Instant.now();
            jdbc.update("insert into exam_sessions (company_id, title, starts_at, ends_at, proctoring, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                    companyId, "upcoming", now.plusSeconds(600), now.plusSeconds(1200), false, userId, now);
            String token = account.path("token").asText();
            mvc.perform(withdrawal(token, "secure-pass-123")).andExpect(status().isConflict());
            jdbc.update("update exam_sessions set starts_at = ?, ends_at = ? where company_id = ?",
                    now.minusSeconds(600), now.plusSeconds(600), companyId);
            mvc.perform(withdrawal(token, "secure-pass-123")).andExpect(status().isConflict());
            jdbc.update("update exam_sessions set ends_at = ? where company_id = ?", now.minusSeconds(1), companyId);
            mvc.perform(withdrawal(token, "secure-pass-123"))
                    .andExpect(status().is(role.equals("MANAGER") ? 403 : 204));
        }
    }

    @Test
    void cancelledAndOtherCompanySessionsDoNotBlockProctorWithdrawalButAdminCannotWithdraw() throws Exception {
        JsonNode account = signupAccount("cancelled-withdraw@example.com");
        long userId = account.path("user").path("id").asLong();
        long companyId = 41003L;
        jdbc.update("update users set role = 'PROCTOR', company_id = ? where user_id = ?", companyId, userId);
        Instant now = Instant.now();
        jdbc.update("insert into exam_sessions (company_id, title, starts_at, ends_at, proctoring, created_by, created_at, canceled_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                companyId, "cancelled", now.plusSeconds(600), now.plusSeconds(1200), false, userId, now, now);
        jdbc.update("insert into exam_sessions (company_id, title, starts_at, ends_at, proctoring, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                41004L, "other company", now.plusSeconds(600), now.plusSeconds(1200), false, userId, now);
        mvc.perform(withdrawal(account.path("token").asText(), "secure-pass-123")).andExpect(status().isNoContent());

        JsonNode admin = signupAccount("admin-withdraw@example.com");
        jdbc.update("update users set role = 'ADMIN' where user_id = ?", admin.path("user").path("id").asLong());
        mvc.perform(withdrawal(admin.path("token").asText(), "secure-pass-123")).andExpect(status().isForbidden());
    }

    @Test
    void withdrawalWaitsForConcurrentCompanySessionCreation() throws Exception {
        JsonNode account = signupAccount("concurrent-withdraw@example.com");
        long userId = account.path("user").path("id").asLong();
        long companyId = 41005L;
        jdbc.update("update users set role = 'PROCTOR', company_id = ? where user_id = ?", companyId, userId);
        CountDownLatch companyLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                accounts.lockByCompanyId(companyId);
                companyLocked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("동시성 테스트 시간 초과");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                Instant now = Instant.now();
                jdbc.update("insert into exam_sessions (company_id, title, starts_at, ends_at, proctoring, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                        companyId, "concurrent", now.plusSeconds(600), now.plusSeconds(1200), false, userId, now);
            }));
            try {
                assertThat(companyLocked.await(10, TimeUnit.SECONDS)).isTrue();
                var withdrawal = executor.submit(() -> mvc.perform(withdrawal(account.path("token").asText(), "secure-pass-123"))
                        .andReturn().getResponse().getStatus());
                release.countDown();
                creation.get(10, TimeUnit.SECONDS);
                assertThat(withdrawal.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            } finally {
                release.countDown();
            }
        }
    }

    private JsonNode signupAccount(String email) throws Exception {
        String code = requestVerificationCode(email);
        MvcResult result = mvc.perform(signup("테스트 사용자", email, "secure-pass-123", code))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.Credentials(email, password))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder passwordChange(
            String token, String current, String replacement) throws Exception {
        return patch("/api/auth/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.PasswordChangeRequest(current, replacement)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withdrawal(
            String token, String current) throws Exception {
        return post("/api/auth/withdrawal").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.WithdrawalRequest(current)));
    }

    private boolean tryRefresh(String token, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            auth.refresh(token);
            return true;
        } catch (UnauthorizedException e) {
            return false;
        }
    }

    private boolean trySignup(String email, String code, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            auth.signup(new AuthDtos.SignupRequest("재가입 사용자", email, "secure-pass-123", code));
            return true;
        } catch (ConflictException | IllegalArgumentException e) {
            return false;
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
            String path, String refreshToken) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.RefreshRequest(refreshToken)));
    }

    private String requestVerificationCode(String email) throws Exception {
        AuthTestSupport.prepareMail(mail);
        mvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthDtos.EmailVerificationRequest(email))))
                .andExpect(status().isAccepted());
        return AuthTestSupport.sentCode(mail);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(
            String name, String email, String password, String code) throws Exception {
        return post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.SignupRequest(name, email, password, code)));
    }
}
