package com.pair.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.attempt.AttemptStore;
import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.domain.ProctorEvent;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.service.AttemptSubmitter;
import com.pair.exam.service.AutoSubmitService;
import com.pair.exam.service.ExamEntryService;
import com.pair.exam.service.ExamSessionService;
import com.pair.exam.service.InviteTokens;
import com.pair.exam.service.InvitesCreated;
import com.pair.exam.service.ProctorService;
import com.pair.exam.service.ReminderService;
import com.pair.exam.service.SessionNotice;
import com.pair.exam.service.SessionResultService;
import com.pair.exam.service.SnapshotStorage;
import com.pair.exam.web.ExamDtos.AddParticipantsResult;
import com.pair.exam.web.ExamDtos.ConsentRequest;
import com.pair.exam.web.ExamDtos.EntryInfo;
import com.pair.exam.web.ExamDtos.ParticipantInput;
import com.pair.exam.web.ExamDtos.ProctorEventRequest;
import com.pair.exam.web.ExamDtos.SessionRequest;
import com.pair.exam.web.ExamDtos.SessionSummary;

/**
 * 회차 전체 흐름 통합 테스트 (H2 메모리 DB).
 * 응시(attempt) 생성·제출은 팀원 1 영역이라 AttemptSubmitter 를 가짜로 바꾸고, 메일 서버도 가짜로 둔다.
 * 서버 시각은 MutableClock 으로 움직인다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
@RecordApplicationEvents
class ExamFlowIntegrationTest {

    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");
    /** 회차에는 기업 실전(LIVE) 과제를 건다. 내용 파일은 원본(order-deadlock, pricing-plan) 것을 쓴다 */
    static final String TASK = com.pair.task.TestTasks.LIVE_DEV;
    static final String TASK2 = com.pair.task.TestTasks.LIVE_BIZ;
    /** 최소한의 JPEG (FF D8 FF 로 시작) */
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

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
    @Autowired AutoSubmitService autoSubmit;
    @Autowired ProctorService proctor;
    @Autowired SessionResultService results;
    @Autowired ExamParticipantRepository participants;
    @Autowired AttemptStore attemptStore;
    @Autowired ObjectMapper json;
    @Autowired ReminderService reminders;
    @Autowired SnapshotStorage storage;
    @Autowired ApplicationEvents appEvents;

    @MockitoBean AttemptSubmitter attempts;
    @MockitoBean JavaMailSender mail;

    @Autowired com.pair.task.TaskRecordRepository taskRecords;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        // 테스트 DB 는 Flyway 를 안 돌리므로 V3 시드와 같은 과제 행을 넣는다
        com.pair.task.TestTasks.seed(taskRecords);
        when(attempts.submitAll(anyCollection())).thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(0)).size());
        when(attempts.submitOne(anyString())).thenReturn(true);
    }

    // ── 회차 ──

    @Test
    @DisplayName("회차를 만들고 목록에서 인원 수를 함께 본다")
    void createAndList() {
        Long company = 101L;
        SessionSummary s = createSession(company, true);
        sessions.addParticipants(s.id(), List.of(new ParticipantInput("김응시", "a@test.dev")));

        List<SessionSummary> list = sessions.list(company);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).participants()).isEqualTo(1);
        assertThat(list.get(0).phase()).isEqualTo(ExamSession.Phase.UPCOMING);
    }

    @Test
    @DisplayName("시작 시각이 지난 회차는 수정·취소할 수 없다")
    void cannotEditAfterStart() {
        SessionSummary s = createSession(102L, true);
        clock.set(s.startsAt().plusSeconds(1));

        assertThatThrownBy(() -> sessions.cancel(s.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.update(s.id(), request(102L, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("취소: 지우지 않고 canceled_at 을 남기고, 입장·시작·자동 제출 대상에서 빠지며 대상자에게 안내 메일을 보낸다")
    void cancelKeepsRecordAndNotifies() {
        SessionSummary s = createSession(114L, false);
        String token = invite(s.id(), "cancel@test.dev");

        SessionSummary canceled = sessions.cancel(s.id());

        assertThat(canceled.phase()).isEqualTo(ExamSession.Phase.CANCELED);
        assertThat(canceled.canceledAt()).isEqualTo(T0);
        assertThat(sessions.list(114L)).singleElement().extracting(SessionSummary::phase).isEqualTo(ExamSession.Phase.CANCELED);
        assertThat(appEvents.stream(SessionNotice.class))
                .anySatisfy(n -> assertThat(n.kind()).isEqualTo(SessionNotice.Kind.CANCELED));
        verify(mail, timeout(3000).atLeastOnce()).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>argThat(
                m -> m.getSubject() != null && m.getSubject().contains("취소")));

        assertThatThrownBy(() -> entry.enter(token)).hasMessageContaining("취소");
        assertThatThrownBy(() -> sessions.cancel(s.id())).hasMessageContaining("취소한 회차");
        clock.set(s.startsAt());
        assertThatThrownBy(() -> entry.start(token)).hasMessageContaining("취소");
        clock.set(s.endsAt().plusSeconds(1));
        assertThat(autoSubmit.dueSessionIds()).doesNotContain(s.id());
    }

    @Test
    @DisplayName("시작 전 수정: 일시가 바뀌면 초대 메일을 새 링크로 다시 보내고, 이름만 바뀌면 변경 안내, 같으면 아무것도 안 보낸다")
    void updateNotifiesOnlyWhenChanged() {
        SessionSummary s = createSession(115L, true);
        String oldToken = invite(s.id(), "change@test.dev");
        SessionRequest same = new SessionRequest(115L, s.title(), s.startsAt(), s.endsAt(), true, List.of(TASK));

        sessions.update(s.id(), same);
        assertThat(appEvents.stream(SessionNotice.class)).isEmpty();

        sessions.update(s.id(), new SessionRequest(115L, s.title() + " 수정", s.startsAt(), s.endsAt(), true, List.of(TASK)));
        assertThat(appEvents.stream(SessionNotice.class)).singleElement()
                .satisfies(n -> assertThat(n.kind()).isEqualTo(SessionNotice.Kind.CHANGED));

        sessions.update(s.id(), new SessionRequest(115L, s.title() + " 수정", s.startsAt().plusSeconds(60), s.endsAt(), true, List.of(TASK)));
        String newToken = lastToken(participant(s.id(), "change@test.dev").getId());
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(appEvents.stream(SessionNotice.class)).hasSize(1);
    }

    @Test
    @DisplayName("엑셀 양식: 내려받은 양식을 그대로 읽을 수 있고, 결과 엑셀이 만들어진다")
    void xlsxRoundTrip() {
        byte[] template = com.pair.exam.service.ExamWorkbooks.participantsTemplate();
        var rows = com.pair.exam.service.ParticipantCsvParser.parse(template, 1000);
        assertThat(rows).as("지우지 않은 예시 행은 등록하지 않는다").isEmpty();

        SessionSummary s = createSession(116L, true);
        invite(s.id(), "x@test.dev");
        byte[] xlsx = results.xlsx(s.id());
        assertThat(com.pair.exam.service.ExamWorkbooks.isXlsx(xlsx)).isTrue();
        // 눈으로 확인하려고 파일로 남긴다 (build/xlsx-samples)
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "xlsx-samples");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve("대상자_양식.xlsx"), template);
            java.nio.file.Files.write(dir.resolve("회차_결과.xlsx"), xlsx);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("명단에서 빼기: 시작 전·입장 전만, 다른 회차 번호로는 못 뺀다")
    void removeParticipant() {
        SessionSummary s = createSession(121L, false);
        String entered = invite(s.id(), "in@test.dev");
        invite(s.id(), "out@test.dev");
        entry.enter(entered);
        Long out = participant(s.id(), "out@test.dev").getId();
        Long in = participant(s.id(), "in@test.dev").getId();

        assertThatThrownBy(() -> sessions.removeParticipant(s.id() + 999, out)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> sessions.removeParticipant(s.id(), in)).hasMessageContaining("입장한");
        sessions.removeParticipant(s.id(), out);

        assertThat(participants.findBySessionIdOrderByIdAsc(s.id())).extracting(ExamParticipant::getEmail).containsExactly("in@test.dev");
    }

    @Test
    @DisplayName("없는 과제로는 회차를 만들 수 없다")
    void rejectsUnknownTask() {
        SessionRequest req = new SessionRequest(103L, "t", T0.plusSeconds(60), T0.plusSeconds(3600), true, List.of("no-such-task"));
        assertThatThrownBy(() -> sessions.create(req, 1L)).isInstanceOf(NoSuchElementException.class);
    }

    // ── 대상자 ──

    @Test
    @DisplayName("대상자 추가: 형식 오류·목록 안 중복·이미 등록된 이메일은 이유와 함께 건너뛴다")
    void addParticipantsRejectsBadRows() {
        SessionSummary s = createSession(104L, true);
        sessions.addParticipants(s.id(), List.of(new ParticipantInput("기존", "old@test.dev")));

        AddParticipantsResult r = sessions.addParticipants(s.id(), List.of(
                new ParticipantInput("정상", "Ok@Test.dev"),
                new ParticipantInput("형식오류", "not-an-email"),
                new ParticipantInput("중복", "ok@test.dev"),
                new ParticipantInput("기존", "OLD@test.dev")));

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.rejected()).extracting(x -> x.line() + ":" + x.reason())
                .containsExactly("2:이메일 형식이 아닙니다.", "3:목록 안에서 이메일이 중복됩니다.", "4:이미 등록된 대상자입니다.");
    }

    @Test
    @DisplayName("CSV 업로드: 헤더 순서가 달라도 읽고, 거절 사유는 CSV 줄 번호로, 원본은 저장소에 남긴다")
    void importCsv() {
        SessionSummary s = createSession(105L, true);
        byte[] csv = "\uFEFF이메일,이름\r\nx1@test.dev,일번\r\n,\r\nbad,\"이, 번\"\r\n".getBytes(StandardCharsets.UTF_8);

        AddParticipantsResult r = sessions.importCsv(s.id(), csv, false);

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.rejected()).singleElement().satisfies(x -> {
            assertThat(x.line()).isEqualTo(4);
            assertThat(x.name()).isEqualTo("이, 번");
        });
        String key = sessionsRepo().findById(s.id()).orElseThrow().getParticipantsCsvKey();
        assertThat(key).startsWith("participant-csv/" + s.id() + "/");
        assertThat(storage.get(key)).contains(csv);
    }

    @Test
    @DisplayName("CSV 미리 보기: 저장하지 않고 들어갈 인원과 거절 사유만 돌려준다. 엑셀 한글판(MS949) CSV 도 읽는다")
    void previewCsv() {
        SessionSummary s = createSession(116L, true);
        invite(s.id(), "dup@test.dev");
        byte[] csv = "이름,이메일\r\n새사람,new@test.dev\r\n기존,dup@test.dev\r\n".getBytes(java.nio.charset.Charset.forName("MS949"));

        AddParticipantsResult r = sessions.importCsv(s.id(), csv, true);

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.rejected()).singleElement().satisfies(x -> {
            assertThat(x.line()).isEqualTo(3);
            assertThat(x.reason()).contains("이미 등록");
        });
        assertThat(participants.findBySessionIdOrderByIdAsc(s.id())).hasSize(1); // 저장 안 함
        assertThat(sessionsRepo().findById(s.id()).orElseThrow().getParticipantsCsvKey()).isNull();
    }

    @Test
    @DisplayName("입장 토큰: DB 에는 SHA-256 해시만 있고, 다시 보내면 새 토큰이 나와 이전 링크는 막힌다")
    void tokenHashAndReissue() {
        SessionSummary s = createSession(117L, true);
        String first = invite(s.id(), "token@test.dev");
        ExamParticipant p = participant(s.id(), "token@test.dev");
        assertThat(p.getInviteTokenHash()).hasSize(64).isEqualTo(InviteTokens.hash(first)).isNotEqualTo(first);

        clock.advance(Duration.ofMinutes(1));
        sessions.resendInvite(s.id(), p.getId());
        String second = lastToken(p.getId());

        assertThat(second).isNotEqualTo(first);
        assertThat(entry.info(second).email()).isEqualTo("token@test.dev");
        assertThatThrownBy(() -> entry.info(first)).isInstanceOf(NoSuchElementException.class);
        assertThat(participant(s.id(), "token@test.dev").getInvitedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("하루 전 알림: 시작 24시간 안에 들어오면 한 번만 보내고, 24시간 안에 초대된 사람은 건너뛴다")
    void reminderOncePerParticipant() throws InterruptedException {
        SessionRequest req = new SessionRequest(118L, "알림 회차", T0.plus(Duration.ofDays(3)), T0.plus(Duration.ofDays(3)).plusSeconds(3600),
                false, List.of(TASK));
        SessionSummary s = sessions.create(req, 1L);
        invite(s.id(), "early@test.dev");
        awaitInviteStatus(s.id(), "early@test.dev", ExamParticipant.InviteStatus.SENT);

        clock.set(s.startsAt().minus(Duration.ofHours(25)));
        assertThat(reminders.claimDue()).isEmpty(); // 아직 24시간 전이 아님

        clock.set(s.startsAt().minus(Duration.ofHours(23)));
        invite(s.id(), "late@test.dev"); // 24시간 안에 초대됨
        awaitInviteStatus(s.id(), "late@test.dev", ExamParticipant.InviteStatus.SENT);
        List<Long> due = reminders.claimDue();

        assertThat(due).containsExactly(participant(s.id(), "early@test.dev").getId());
        assertThat(participant(s.id(), "early@test.dev").getRemindedAt()).isEqualTo(clock.instant());
        assertThat(reminders.claimDue()).isEmpty(); // 두 번 보내지 않음
    }

    @Test
    @DisplayName("초대 메일: 등록이 커밋된 뒤 비동기로 보내고 성공·실패를 기록한다")
    void inviteMailAsync() throws InterruptedException {
        SessionSummary s = createSession(106L, true);
        doThrowForAddress("fail@test.dev");

        sessions.addParticipants(s.id(), List.of(new ParticipantInput("성공", "ok@test.dev"),
                new ParticipantInput("실패", "fail@test.dev")));

        verify(mail, timeout(3000).times(2)).send(any(SimpleMailMessage.class));
        awaitInviteStatus(s.id(), "ok@test.dev", ExamParticipant.InviteStatus.SENT);
        awaitInviteStatus(s.id(), "fail@test.dev", ExamParticipant.InviteStatus.FAILED);
    }

    // ── 입장·시작 ──

    @Test
    @DisplayName("서버 시각 기준: 시작 전에는 대기만, 시작 시각이 되면 시작, 감독 회차는 동의가 먼저")
    void startOnlyByServerClock() {
        SessionSummary s = createSession(107L, true);
        String token = invite(s.id(), "start@test.dev");

        EntryInfo waiting = entry.enter(token);
        assertThat(waiting.phase()).isEqualTo(ExamSession.Phase.UPCOMING);
        assertThatThrownBy(() -> entry.start(token)).hasMessageContaining("아직 시작 시각이 아닙니다");

        clock.set(s.startsAt());
        assertThatThrownBy(() -> entry.start(token)).hasMessageContaining("감독 동의");

        entry.consent(token, consent());
        EntryInfo started = entry.start(token);
        EntryInfo again = entry.start(token);

        assertThat(started.attempts()).containsOnlyKeys(TASK);
        assertThat(started.tasks()).singleElement().satisfies(t -> assertThat(t.submitted()).isFalse());
        assertThat(again.attempts()).isEqualTo(started.attempts());
        verify(attempts, times(1)).prepare(any(), eq(com.pair.task.TestTasks.SELF_DEV)); // 두 번 눌러도 응시는 하나 (내용은 원본 과제)
    }

    @Test
    @DisplayName("과제별 제출: 한 과제만 제출하면 그 과제만 넘기고, 모든 과제를 내면 대상자도 제출 완료")
    void submitPerTask() {
        SessionSummary s = sessions.create(new SessionRequest(119L, "두 과제", clock.instant().plus(Duration.ofMinutes(10)),
                clock.instant().plus(Duration.ofMinutes(70)), false, List.of(TASK, TASK2)), 1L);
        String token = invite(s.id(), "multi@test.dev");
        clock.set(s.startsAt());
        EntryInfo started = entry.start(token);
        String a1 = started.attempts().get(TASK);
        String a2 = started.attempts().get(TASK2);

        EntryInfo afterFirst = entry.submitTask(token, TASK);

        verify(attempts).submitOne(a1);
        verify(attempts, times(0)).submitOne(a2);
        assertThat(afterFirst.submittedAt()).isNull();
        assertThat(afterFirst.tasks()).extracting(t -> t.taskId() + ":" + t.submitted()).containsExactly(TASK + ":true", TASK2 + ":false");

        EntryInfo afterAll = entry.submitTask(token, TASK2);

        assertThat(afterAll.submittedAt()).isEqualTo(s.startsAt());
        assertThatThrownBy(() -> entry.submitTask(token, "not-in-session")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("형식이 틀리거나 없는 토큰은 404")
    void badToken() {
        assertThatThrownBy(() -> entry.info("short")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> entry.info("A".repeat(43))).isInstanceOf(NoSuchElementException.class);
    }

    // ── 종료·자동 제출 ──

    @Test
    @DisplayName("종료 시각이 지나면 시작한 응시자만 자동 제출하고, 직접 제출한 응시자는 그대로 둔다")
    void autoSubmitAtEnd() {
        SessionSummary s = createSession(108L, false);
        String solving = invite(s.id(), "solving@test.dev");
        String manual = invite(s.id(), "manual@test.dev");
        String absent = invite(s.id(), "absent@test.dev");
        clock.set(s.startsAt());
        entry.start(solving);
        entry.start(manual);
        entry.submit(manual);

        clock.set(s.endsAt().plusMillis(800));
        assertThat(autoSubmit.dueSessionIds()).contains(s.id());
        int n = autoSubmit.close(s.id());

        assertThat(n).isEqualTo(1);
        assertThat(entry.info(solving).submittedAt()).isEqualTo(s.endsAt().plusMillis(800));
        assertThat(entry.info(manual).submittedAt()).isEqualTo(s.startsAt()); // 직접 제출한 시각 그대로
        assertThat(entry.info(absent).submittedAt()).isNull();
        assertThat(autoSubmit.dueSessionIds()).doesNotContain(s.id());
        assertThat(autoSubmit.close(s.id())).isZero(); // 두 번 돌아도 안전

        SessionResultService.Result r = results.result(s.id());
        assertThat(r.metrics().autoSubmitLagMs()).isEqualTo(800L);
    }

    @Test
    @DisplayName("종료 뒤에는 시작도 직접 제출도 막힌다")
    void blockedAfterEnd() {
        SessionSummary s = createSession(109L, false);
        String token = invite(s.id(), "late@test.dev");
        clock.set(s.endsAt());

        assertThatThrownBy(() -> entry.start(token)).hasMessageContaining("끝난 시험");
        assertThatThrownBy(() -> entry.enter(token)).hasMessageContaining("끝난 시험");
    }

    // ── 감독 이벤트·사진 ──

    @Test
    @DisplayName("카메라 이벤트와 사진 1장을 저장하고, 회차 종료 30일이 지나면 사진만 지운다")
    void proctorEventAndPurge() {
        SessionSummary s = createSession(110L, true);
        String token = startWithConsent(s, "cam@test.dev");

        var view = proctor.record(token, camEvent(s.startsAt().plusSeconds(30), "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(JPEG)));
        proctor.record(token, new ProctorEventRequest(ProctorEvent.Type.TAB_HIDDEN, ProctorEvent.Source.BROWSER,
                s.startsAt().plusSeconds(40), 1200, null, null, null));

        assertThat(view.snapshot()).isEqualTo("ok");
        assertThat(proctor.snapshot(s.id(), view.id())).isEqualTo(JPEG);

        clock.set(s.endsAt().plus(Duration.ofDays(29)));
        assertThat(proctor.purgeExpiredSnapshots()).isZero(); // 아직 30일 전
        clock.set(s.endsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(proctor.purgeExpiredSnapshots()).isEqualTo(1);
        assertThatThrownBy(() -> proctor.snapshot(s.id(), view.id())).hasMessageContaining("삭제된 사진");

        Long pid = participant(s.id(), "cam@test.dev").getId();
        assertThat(proctor.eventsOf(s.id(), pid)).extracting(e -> e.type() + "/" + e.snapshot())
                .containsExactly("NO_FACE/deleted", "TAB_HIDDEN/null");
    }

    @Test
    @DisplayName("사진 검증: JPEG 가 아니거나 브라우저 이벤트에 붙은 사진, 시험 시간 밖 이벤트는 거절")
    void proctorEventValidation() {
        SessionSummary s = createSession(111L, true);
        String token = startWithConsent(s, "val@test.dev");
        String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        assertThatThrownBy(() -> proctor.record(token, camEvent(s.startsAt(), png))).hasMessageContaining("JPEG");
        assertThatThrownBy(() -> proctor.record(token, new ProctorEventRequest(ProctorEvent.Type.TAB_HIDDEN,
                ProctorEvent.Source.BROWSER, s.startsAt(), 0, null, null, Base64.getEncoder().encodeToString(JPEG))))
                .hasMessageContaining("브라우저 이벤트");
        assertThatThrownBy(() -> proctor.record(token, camEvent(s.startsAt().minus(Duration.ofHours(1)), null)))
                .hasMessageContaining("시험 시간 밖");
    }

    @Test
    @DisplayName("담당자 판정: OK/SUSPECT 를 남기고 메모·판정자·시각을 기록, 지우면 미확인으로 돌아간다")
    void reviewEvent() {
        SessionSummary s = createSession(120L, true);
        String token = startWithConsent(s, "review@test.dev");
        var ev = proctor.record(token, camEvent(s.startsAt(), null));

        var suspect = proctor.review(s.id(), ev.id(), ProctorEvent.Review.SUSPECT, " 휴대폰 보임 ", 7L);
        assertThat(suspect.review()).isEqualTo(ProctorEvent.Review.SUSPECT);
        assertThat(suspect.reviewNote()).isEqualTo("휴대폰 보임");
        assertThat(suspect.reviewedBy()).isEqualTo(7L);
        assertThat(suspect.reviewedAt()).isEqualTo(clock.instant());

        var cleared = proctor.review(s.id(), ev.id(), null, "무시됨", 7L);
        assertThat(cleared.review()).isNull();
        assertThat(cleared.reviewedBy()).isNull();
        assertThatThrownBy(() -> proctor.review(s.id(), ev.id(), ProctorEvent.Review.OK, null, null))
                .hasMessageContaining("판정한 사람");
        assertThatThrownBy(() -> proctor.review(s.id() + 999, ev.id(), ProctorEvent.Review.OK, null, 7L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("다른 회차 번호로는 사진을 볼 수 없다")
    void snapshotBelongsToSession() {
        SessionSummary s = createSession(112L, true);
        String token = startWithConsent(s, "own@test.dev");
        var view = proctor.record(token, camEvent(s.startsAt(), Base64.getEncoder().encodeToString(JPEG)));

        assertThatThrownBy(() -> proctor.snapshot(s.id() + 999, view.id())).isInstanceOf(NoSuchElementException.class);
    }

    // ── 결과 ──

    @Test
    @DisplayName("결과: 점수 순위(동점 같은 순위), 분포, 감독 이벤트 합계, 데이터 절감률, CSV")
    void results() throws Exception {
        SessionSummary s = createSession(113L, true);
        String a = startWithConsent(s, "a@test.dev");
        String b = startWithConsent(s, "b@test.dev");
        String c = startWithConsent(s, "c@test.dev");
        invite(s.id(), "=cmd@test.dev"); // CSV 주입 방어 확인용 (미응시)
        writeReport(entry.info(a).attempts().get(TASK), 80);
        writeReport(entry.info(b).attempts().get(TASK), 80);
        writeReport(entry.info(c).attempts().get(TASK), 55);
        proctor.record(c, camEvent(s.startsAt().plusSeconds(5), Base64.getEncoder().encodeToString(JPEG)));

        SessionResultService.Result r = results.result(s.id());

        assertThat(r.scored()).isEqualTo(3);
        assertThat(r.rows()).extracting(row -> row.rank() + ":" + row.score()).containsExactly("1:80", "1:80", "3:55", "0:null");
        assertThat(r.average()).isEqualTo(71.667);
        assertThat(r.distribution().get(8).count()).isEqualTo(2);
        assertThat(r.eventTotals()).containsEntry(ProctorEvent.Type.NO_FACE, 1L);
        assertThat(r.metrics().snapshotBytes()).isEqualTo(JPEG.length);
        assertThat(r.metrics().savedRatio()).isGreaterThan(0.999);

        String csv = results.csv(s.id());
        assertThat(csv).startsWith("﻿순위,이름,이메일");
        assertThat(csv).contains(",'=cmd@test.dev,");
    }

    // ── 도우미 ──

    private SessionSummary createSession(Long company, boolean proctoring) {
        return sessions.create(request(company, proctoring), 1L);
    }

    private SessionRequest request(Long company, boolean proctoring) {
        return new SessionRequest(company, "2026 하반기 실전", clock.instant().plus(Duration.ofMinutes(10)),
                clock.instant().plus(Duration.ofMinutes(70)), proctoring, List.of(TASK));
    }

    /** 토큰 원문은 DB 에 없으므로 메일러로 가는 이벤트에서 꺼낸다 */
    private String invite(Long sessionId, String email) {
        sessions.addParticipants(sessionId, List.of(new ParticipantInput("응시자", email)));
        return lastToken(participant(sessionId, email).getId());
    }

    private String lastToken(Long participantId) {
        return appEvents.stream(InvitesCreated.class).flatMap(e -> e.invites().stream())
                .filter(i -> i.participantId().equals(participantId))
                .reduce((a, b) -> b).orElseThrow().token();
    }

    @Autowired com.pair.exam.repository.ExamSessionRepository sessionRepository;

    private com.pair.exam.repository.ExamSessionRepository sessionsRepo() {
        return sessionRepository;
    }

    private String startWithConsent(SessionSummary s, String email) {
        String token = invite(s.id(), email);
        entry.consent(token, consent());
        clock.set(s.startsAt());
        entry.start(token);
        return token;
    }

    private ExamParticipant participant(Long sessionId, String email) {
        return participants.findBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(p -> p.getEmail().equals(email)).findFirst().orElseThrow();
    }

    private ConsentRequest consent() {
        return new ConsentRequest("v1", json.createObjectNode().put("camera", "pass"));
    }

    private static ProctorEventRequest camEvent(Instant at, String snapshot) {
        return new ProctorEventRequest(ProctorEvent.Type.NO_FACE, ProctorEvent.Source.PC_CAM, at, 2100,
                new BigDecimal("0.910"), null, snapshot);
    }

    private void writeReport(String attemptId, int total) {
        var items = json.createArrayNode();
        items.addObject().put("key", "debug").put("name", "디버깅").put("max", 100).put("score", total);
        var report = json.createObjectNode().put("total", total).put("max", 100);
        report.set("items", items);
        attemptStore.writeReport(attemptId, report);
    }

    private void doThrowForAddress(String address) {
        org.mockito.Mockito.doAnswer(inv -> {
            SimpleMailMessage m = inv.getArgument(0);
            if (m.getTo() != null && List.of(m.getTo()).contains(address)) throw new MailSendException("smtp down");
            return null;
        }).when(mail).send(any(SimpleMailMessage.class));
    }

    /** 메일 결과 저장은 다른 스레드라 잠깐 기다린다 (최대 3초) */
    private void awaitInviteStatus(Long sessionId, String email, ExamParticipant.InviteStatus expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (participant(sessionId, email).getInviteStatus() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(participant(sessionId, email).getInviteStatus()).isEqualTo(expected);
    }
}
