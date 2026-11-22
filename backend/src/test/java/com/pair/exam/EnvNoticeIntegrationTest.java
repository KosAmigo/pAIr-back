package com.pair.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.service.EnvCheckService;
import com.pair.exam.service.ExamEntryService;
import com.pair.exam.service.ExamSessionService;
import com.pair.exam.service.InvitesCreated;
import com.pair.exam.service.NoticeService;
import com.pair.exam.web.ExamDtos.ConsentRequest;
import com.pair.exam.web.ExamDtos.EnvCheckSource;
import com.pair.exam.web.ExamDtos.ParticipantInput;
import com.pair.exam.web.ExamDtos.SessionRequest;
import com.pair.exam.web.ExamDtos.SessionSummary;

import jakarta.mail.internet.MimeMessage;

/** 환경 점검 콘솔 확인(진행 상태·저장 결과)과 안내 메시지(전송·읽음) 통합 테스트 */
@SpringBootTest
@ActiveProfiles("examtest")
@RecordApplicationEvents
class EnvNoticeIntegrationTest {

    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired MutableClock clock;
    @Autowired ExamSessionService sessions;
    @Autowired ExamEntryService entry;
    @Autowired EnvCheckService envCheck;
    @Autowired NoticeService notices;
    @Autowired ExamParticipantRepository participants;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents appEvents;
    @Autowired com.pair.task.TaskRecordRepository taskRecords;
    @Autowired com.pair.task.TaskFileRecordRepository taskFiles;
    @Autowired com.pair.task.TaskQuestionRecordRepository taskQuestions;

    @MockitoBean com.pair.exam.service.AttemptSubmitter attempts;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage((jakarta.mail.Session) null));
        com.pair.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
    }

    @Test
    @DisplayName("점검 진행 상태를 올리면 콘솔이 실시간(LIVE)으로 보고, 동의로 저장되면 저장 결과(SAVED)를 본다")
    void liveThenSaved() {
        SessionSummary s = createSession(301L, true);
        String token = invite(s.id(), "live@test.dev");
        Long pid = pid(s.id(), "live@test.dev");

        assertThat(envCheck.view(s.id(), pid)).isNull(); // 아직 아무것도 없다

        envCheck.saveProgress(token, json.createObjectNode().put("light", "warn"));
        var live = envCheck.view(s.id(), pid);
        assertThat(live.source()).isEqualTo(EnvCheckSource.LIVE);
        assertThat(live.result().get("light").asText()).isEqualTo("warn");
        assertThat(live.updatedAt()).isEqualTo(T0);

        clock.advance(Duration.ofSeconds(30));
        envCheck.saveProgress(token, json.createObjectNode().put("light", "pass"));
        assertThat(envCheck.view(s.id(), pid).result().get("light").asText()).isEqualTo("pass");

        clock.advance(Duration.ofSeconds(30));
        entry.consent(token, new ConsentRequest("v1", json.createObjectNode().put("light", "fail")));
        var saved = envCheck.view(s.id(), pid);
        assertThat(saved.source()).isEqualTo(EnvCheckSource.SAVED);
        assertThat(saved.result().get("light").asText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("저장 뒤 다시 점검하며 올린 진행 상태가 더 최근이면 실시간 값을 보여 준다")
    void newerProgressWinsOverSaved() {
        SessionSummary s = createSession(302L, true);
        String token = invite(s.id(), "again@test.dev");
        Long pid = pid(s.id(), "again@test.dev");
        entry.consent(token, new ConsentRequest("v1", json.createObjectNode().put("light", "fail")));

        clock.advance(Duration.ofSeconds(10));
        envCheck.saveProgress(token, json.createObjectNode().put("light", "pass"));

        assertThat(envCheck.view(s.id(), pid).source()).isEqualTo(EnvCheckSource.LIVE);
    }

    @Test
    @DisplayName("진행 상태: 잘못된 링크·감독 안 쓰는 회차·너무 큰 값·끝난 회차는 거절하고, 다른 회차 번호로는 볼 수 없다")
    void progressRejects() {
        SessionSummary s = createSession(303L, true);
        String token = invite(s.id(), "rej@test.dev");
        Long pid = pid(s.id(), "rej@test.dev");

        assertThatThrownBy(() -> envCheck.saveProgress("x".repeat(43), json.createObjectNode()))
                .isInstanceOf(NoSuchElementException.class);
        var big = json.createObjectNode().put("blob", "a".repeat(EnvCheckService.MAX_CHARS + 1));
        assertThatThrownBy(() -> envCheck.saveProgress(token, big)).hasMessageContaining("너무 깁니다");

        SessionSummary plain = createSession(304L, false);
        String plainToken = invite(plain.id(), "plain@test.dev");
        assertThatThrownBy(() -> envCheck.saveProgress(plainToken, json.createObjectNode())).hasMessageContaining("감독");

        SessionSummary other = createSession(305L, true);
        assertThatThrownBy(() -> envCheck.view(other.id(), pid)).isInstanceOf(NoSuchElementException.class);

        clock.set(s.endsAt().plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> envCheck.saveProgress(token, json.createObjectNode())).hasMessageContaining("끝난");
    }

    @Test
    @DisplayName("안내 메시지: 보내면 응시자가 받고, 확인하면 콘솔 목록에 읽은 시각이 남는다")
    void sendReadFlow() {
        SessionSummary s = createSession(310L, true);
        String token = invite(s.id(), "msg@test.dev");
        Long pid = pid(s.id(), "msg@test.dev");

        var sent = notices.send(s.id(), pid, "  조명이 어둡습니다. 스탠드를 켜 주세요.  ", 7L);

        assertThat(sent.message()).isEqualTo("조명이 어둡습니다. 스탠드를 켜 주세요."); // 앞뒤 공백 정리
        assertThat(sent.readAt()).isNull();
        assertThat(notices.forEntry(token)).extracting(n -> n.id()).containsExactly(sent.id());
        assertThat(notices.listOf(s.id(), pid)).singleElement().satisfies(n -> assertThat(n.readAt()).isNull());

        clock.advance(Duration.ofSeconds(20));
        notices.markRead(token, sent.id());
        notices.markRead(token, sent.id()); // 두 번 눌러도 처음 읽은 시각을 지키고 오류 없음

        assertThat(notices.listOf(s.id(), pid)).singleElement()
                .satisfies(n -> assertThat(n.readAt()).isEqualTo(T0.plusSeconds(20)));
    }

    @Test
    @DisplayName("안내 메시지는 시험 중에도 보낼 수 있고, 새로 보낸 것이 목록 맨 뒤에 온다")
    void sendDuringExam() {
        SessionSummary s = createSession(311L, true);
        String token = invite(s.id(), "during@test.dev");
        Long pid = pid(s.id(), "during@test.dev");
        var first = notices.send(s.id(), pid, "첫째", 7L);
        clock.set(s.startsAt().plusSeconds(60));

        var second = notices.send(s.id(), pid, "둘째", 7L);

        assertThat(notices.forEntry(token)).extracting(n -> n.id()).containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("안내 메시지 검증: 빈 내용·500자 초과·끝난/취소 회차는 거절하고, 남의 메시지는 읽음 처리할 수 없다")
    void noticeRejects() {
        SessionSummary s = createSession(312L, true);
        String token = invite(s.id(), "nrej@test.dev");
        Long pid = pid(s.id(), "nrej@test.dev");

        assertThatThrownBy(() -> notices.send(s.id(), pid, "   ", 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notices.send(s.id(), pid, "가".repeat(NoticeService.MAX_LENGTH + 1), 7L))
                .isInstanceOf(IllegalArgumentException.class);
        notices.send(s.id(), pid, "가".repeat(NoticeService.MAX_LENGTH), 7L); // 정확히 한도는 허용

        SessionSummary other = createSession(313L, true);
        assertThatThrownBy(() -> notices.send(other.id(), pid, "x", 7L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> notices.listOf(other.id(), pid)).isInstanceOf(NoSuchElementException.class);

        String otherToken = invite(other.id(), "other@test.dev");
        Long noticeId = notices.listOf(s.id(), pid).get(0).id();
        assertThatThrownBy(() -> notices.markRead(otherToken, noticeId)).isInstanceOf(NoSuchElementException.class);

        Long otherPid = pid(other.id(), "other@test.dev");
        sessions.cancel(other.id());
        assertThatThrownBy(() -> notices.send(other.id(), otherPid, "x", 7L)).hasMessageContaining("취소");

        clock.set(s.endsAt().plusSeconds(1));
        assertThatThrownBy(() -> notices.send(s.id(), pid, "x", 7L)).hasMessageContaining("끝난");
        assertThat(notices.forEntry(token)).hasSize(1); // 끝난 뒤에도 받은 메시지는 읽을 수 있다
    }

    private SessionSummary createSession(Long company, boolean proctoring) {
        return sessions.create(new SessionRequest(company, "점검 회차", clock.instant().plus(Duration.ofMinutes(10)),
                clock.instant().plus(Duration.ofMinutes(70)), proctoring, List.of(com.pair.task.TestTasks.LIVE_DEV)), 1L);
    }

    private String invite(Long sessionId, String email) {
        sessions.addParticipants(sessionId, List.of(new ParticipantInput("응시자", email)));
        Long id = pid(sessionId, email);
        return appEvents.stream(InvitesCreated.class).flatMap(e -> e.invites().stream())
                .filter(i -> i.participantId().equals(id)).reduce((a, b) -> b).orElseThrow().token();
    }

    private Long pid(Long sessionId, String email) {
        return participants.findBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(p -> p.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }
}
