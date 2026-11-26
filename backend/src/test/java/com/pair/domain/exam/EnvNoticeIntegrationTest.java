package com.pair.domain.exam;

import org.junit.jupiter.api.Tag;

import com.pair.domain.exam.dto.EnvCheckFailureResponse;
import com.pair.domain.exam.dto.EnvCheckResponse;
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
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.service.EnvCheckService;
import com.pair.domain.exam.service.ExamEntryService;
import com.pair.domain.exam.service.ExamSessionService;
import com.pair.domain.exam.dto.InvitesCreatedDto;
import com.pair.domain.exam.service.NoticeService;
import com.pair.domain.exam.dto.ConsentRequest;
import com.pair.domain.exam.dto.ParticipantInputDto;
import com.pair.domain.exam.dto.ExamSessionRequest;
import com.pair.domain.exam.dto.SessionSummaryResponse;

import jakarta.mail.internet.MimeMessage;

/** 환경 점검 콘솔 확인(진행 상태·저장 결과)과 안내 메시지(전송·읽음) 통합 테스트 */
@SpringBootTest
@ActiveProfiles("examtest")
@RecordApplicationEvents
@Tag("slow")
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
    @Autowired com.pair.domain.exam.service.ViewerAccessService viewerAccess;
    @Autowired NoticeService notices;
    @Autowired ExamParticipantRepository participants;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents appEvents;
    @Autowired com.pair.domain.task.repository.TaskRepository taskRecords;
    @Autowired com.pair.domain.task.repository.TaskFileRepository taskFiles;
    @Autowired com.pair.domain.task.repository.TaskQuestionRepository taskQuestions;

    @MockitoBean com.pair.domain.exam.service.AttemptSubmitService attempts;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage((jakarta.mail.Session) null));
        com.pair.domain.task.TestTasks.seed(taskRecords, taskFiles, taskQuestions);
    }

    // ── 감독관 영상 열기 권한 (ViewerAccessService). 컨텍스트를 늘리지 않으려고 같은 설정의 이 클래스에 둔다 ──

    /** 개발용 헤더 모드(examtest)의 로그인 정보. 키는 소문자다 */
    private static java.util.Map<String, String> login(String role, Long company) {
        return company == null
                ? java.util.Map.of("x-pair-user-id", "7", "x-pair-role", role)
                : java.util.Map.of("x-pair-user-id", "7", "x-pair-role", role, "x-pair-company-id", String.valueOf(company));
    }

    private com.pair.domain.exam.dto.ViewerAccessDto.State viewState(java.util.Map<String, String> auth, Long sessionId, Long participantId) {
        return viewerAccess.check(auth, sessionId, participantId).state();
    }

    @Test
    @DisplayName("영상 열기: 진행 중인 회차에 입장한 응시자는 같은 회사 감독관·관리자가 열 수 있다")
    void viewerAllowed() {
        SessionSummaryResponse s = createSession(401L, true);
        String token = invite(s.id(), "view-ok@test.dev");
        entry.enter(token);
        clock.set(s.startsAt());
        Long pid = pid(s.id(), "view-ok@test.dev");

        var r = viewerAccess.check(login("PROCTOR", 401L), s.id(), pid);
        assertThat(r.state()).isEqualTo(com.pair.domain.exam.dto.ViewerAccessDto.State.ALLOWED);
        assertThat(r.userId()).isEqualTo(7L);
        assertThat(viewState(login("ADMIN", null), s.id(), pid)).isEqualTo(com.pair.domain.exam.dto.ViewerAccessDto.State.ALLOWED); // 관리자는 모든 회사
    }

    @Test
    @DisplayName("영상 열기: 로그인 정보가 없거나 틀리거나, 응시자 계정이거나 다른 회사 감독관이면 거절한다")
    void viewerDenied() {
        SessionSummaryResponse s = createSession(402L, true);
        entry.enter(invite(s.id(), "view-no@test.dev"));
        clock.set(s.startsAt());
        Long pid = pid(s.id(), "view-no@test.dev");
        var denied = com.pair.domain.exam.dto.ViewerAccessDto.State.DENIED;

        assertThat(viewState(java.util.Map.of(), s.id(), pid)).isEqualTo(denied);
        assertThat(viewState(java.util.Map.of("x-pair-user-id", "abc", "x-pair-role", "PROCTOR"), s.id(), pid)).isEqualTo(denied);
        assertThat(viewState(java.util.Map.of("x-pair-user-id", "7", "x-pair-role", "NOBODY"), s.id(), pid)).isEqualTo(denied);
        assertThat(viewState(login("USER", null), s.id(), pid)).isEqualTo(denied);
        assertThat(viewState(login("PROCTOR", 999L), s.id(), pid)).isEqualTo(denied); // 다른 회사는 있는지도 알리지 않는다
    }

    @Test
    @DisplayName("영상 열기: 다른 회차의 응시자 번호나 없는 응시자는 거절한다")
    void viewerMismatchedParticipant() {
        SessionSummaryResponse a = createSession(403L, true);
        SessionSummaryResponse b = createSession(403L, true);
        entry.enter(invite(a.id(), "view-a@test.dev"));
        entry.enter(invite(b.id(), "view-b@test.dev"));
        clock.set(a.startsAt());
        var denied = com.pair.domain.exam.dto.ViewerAccessDto.State.DENIED;

        assertThat(viewState(login("PROCTOR", 403L), a.id(), pid(b.id(), "view-b@test.dev"))).isEqualTo(denied);
        assertThat(viewState(login("PROCTOR", 403L), a.id(), 999_999L)).isEqualTo(denied);
    }

    @Test
    @DisplayName("영상 열기: 시작 전이거나 아직 입장하지 않은 응시자는 offline(곧 가능)으로 돌려준다")
    void viewerOfflineBeforeStartOrEntry() {
        SessionSummaryResponse s = createSession(404L, true);
        entry.enter(invite(s.id(), "view-early@test.dev"));
        invite(s.id(), "view-late@test.dev"); // 입장하지 않는다
        Long early = pid(s.id(), "view-early@test.dev");
        Long late = pid(s.id(), "view-late@test.dev");
        var offline = com.pair.domain.exam.dto.ViewerAccessDto.State.OFFLINE;

        assertThat(viewState(login("PROCTOR", 404L), s.id(), early)).isEqualTo(offline); // 시작 전
        clock.set(s.startsAt());
        assertThat(viewState(login("PROCTOR", 404L), s.id(), early)).isEqualTo(com.pair.domain.exam.dto.ViewerAccessDto.State.ALLOWED);
        assertThat(viewState(login("PROCTOR", 404L), s.id(), late)).isEqualTo(offline); // 입장 전
    }

    @Test
    @DisplayName("영상 열기: 끝난 회차와 감독을 쓰지 않는 회차는 열 수 없다")
    void viewerErrorWhenEndedOrNotProctored() {
        SessionSummaryResponse s = createSession(405L, true);
        SessionSummaryResponse plain = createSession(405L, false);
        entry.enter(invite(s.id(), "view-end@test.dev"));
        entry.enter(invite(plain.id(), "view-plain@test.dev"));
        var error = com.pair.domain.exam.dto.ViewerAccessDto.State.ERROR;

        clock.set(s.endsAt());
        assertThat(viewState(login("PROCTOR", 405L), s.id(), pid(s.id(), "view-end@test.dev"))).isEqualTo(error);
        clock.set(plain.startsAt());
        var r = viewerAccess.check(login("PROCTOR", 405L), plain.id(), pid(plain.id(), "view-plain@test.dev"));
        assertThat(r.state()).isEqualTo(error);
        assertThat(r.message()).contains("감독을 쓰지 않는");
    }

    @Test
    @DisplayName("점검 진행 상태를 올리면 콘솔이 실시간(LIVE)으로 보고, 동의로 저장되면 저장 결과(SAVED)를 본다")
    void liveThenSaved() {
        SessionSummaryResponse s = createSession(301L, true);
        String token = invite(s.id(), "live@test.dev");
        Long pid = pid(s.id(), "live@test.dev");

        assertThat(envCheck.view(s.id(), pid)).isNull(); // 아직 아무것도 없다

        envCheck.saveProgress(token, json.createObjectNode().put("light", "warn"));
        var live = envCheck.view(s.id(), pid);
        assertThat(live.source()).isEqualTo(EnvCheckResponse.Source.LIVE);
        assertThat(live.result().get("light").asText()).isEqualTo("warn");
        assertThat(live.updatedAt()).isEqualTo(T0);

        clock.advance(Duration.ofSeconds(30));
        envCheck.saveProgress(token, json.createObjectNode().put("light", "pass"));
        assertThat(envCheck.view(s.id(), pid).result().get("light").asText()).isEqualTo("pass");

        clock.advance(Duration.ofSeconds(30));
        entry.consent(token, new ConsentRequest("v1", json.createObjectNode().put("light", "fail")));
        var saved = envCheck.view(s.id(), pid);
        assertThat(saved.source()).isEqualTo(EnvCheckResponse.Source.SAVED);
        assertThat(saved.result().get("light").asText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("점검 미통과 입장자: 실패 항목이 있는 채 입장한 사람만 모으고, 안내를 보냈는지 함께 보여 준다")
    void failuresOfSession() throws Exception {
        SessionSummaryResponse s = createSession(310L, true);
        String failed = invite(s.id(), "fail@test.dev");
        String clean = invite(s.id(), "clean@test.dev");
        String notEntered = invite(s.id(), "wait@test.dev");
        Long failedId = pid(s.id(), "fail@test.dev");

        entry.consent(failed, new ConsentRequest("v1", json.readTree("""
                {"pc":{"items":[{"id":"light","label":"밝기","status":"fail","detail":"너무 어둡습니다"},
                                {"id":"face","label":"얼굴","status":"pass","detail":""}]},
                 "phone":{"items":[{"id":"desk","label":"책상 위","status":"fail","detail":"스캔 실패"},
                                   {"id":"hand","label":"손","status":"warn","detail":""}]}}""")));
        entry.enter(failed);
        entry.consent(clean, new ConsentRequest("v1", json.readTree("{\"pc\":{\"items\":[{\"id\":\"light\",\"status\":\"pass\"}]},\"phone\":null}")));
        entry.enter(clean);
        entry.consent(notEntered, new ConsentRequest("v1", json.readTree("{\"pc\":{\"items\":[{\"id\":\"light\",\"status\":\"fail\"}]}}"))); // 점검만 하고 입장은 안 함

        var list = envCheck.failuresOf(s.id());

        assertThat(list).singleElement().satisfies(f -> {
            assertThat(f.participantId()).isEqualTo(failedId);
            assertThat(f.failed()).extracting(EnvCheckFailureResponse.Item::id).containsExactly("light", "desk"); // warn 은 제외
            assertThat(f.failed().get(0).detail()).isEqualTo("너무 어둡습니다");
            assertThat(f.noticeSent()).isFalse();
        });

        notices.send(s.id(), failedId, "방을 더 밝게 해 주세요.", 1L);
        assertThat(envCheck.failuresOf(s.id())).singleElement().satisfies(f -> assertThat(f.noticeSent()).isTrue());
    }

    @Test
    @DisplayName("점검 미통과 입장자: 입장한 사람이 없거나 점검 JSON 모양이 달라도 오류 없이 빈 목록")
    void failuresOfEmptyAndMalformed() throws Exception {
        SessionSummaryResponse s = createSession(311L, true);
        assertThat(envCheck.failuresOf(s.id())).isEmpty();

        String token = invite(s.id(), "odd@test.dev");
        entry.consent(token, new ConsentRequest("v1", json.readTree("{\"pc\":{\"items\":\"not-a-list\"},\"phone\":7}")));
        entry.enter(token);
        assertThat(envCheck.failuresOf(s.id())).isEmpty();
    }

    @Test
    @DisplayName("저장 뒤 다시 점검하며 올린 진행 상태가 더 최근이면 실시간 값을 보여 준다")
    void newerProgressWinsOverSaved() {
        SessionSummaryResponse s = createSession(302L, true);
        String token = invite(s.id(), "again@test.dev");
        Long pid = pid(s.id(), "again@test.dev");
        entry.consent(token, new ConsentRequest("v1", json.createObjectNode().put("light", "fail")));

        clock.advance(Duration.ofSeconds(10));
        envCheck.saveProgress(token, json.createObjectNode().put("light", "pass"));

        assertThat(envCheck.view(s.id(), pid).source()).isEqualTo(EnvCheckResponse.Source.LIVE);
    }

    @Test
    @DisplayName("진행 상태: 잘못된 링크·감독 안 쓰는 회차·너무 큰 값·끝난 회차는 거절하고, 다른 회차 번호로는 볼 수 없다")
    void progressRejects() {
        SessionSummaryResponse s = createSession(303L, true);
        String token = invite(s.id(), "rej@test.dev");
        Long pid = pid(s.id(), "rej@test.dev");

        assertThatThrownBy(() -> envCheck.saveProgress("x".repeat(43), json.createObjectNode()))
                .isInstanceOf(NoSuchElementException.class);
        var big = json.createObjectNode().put("blob", "a".repeat(EnvCheckService.MAX_CHARS + 1));
        assertThatThrownBy(() -> envCheck.saveProgress(token, big)).hasMessageContaining("너무 깁니다");

        SessionSummaryResponse plain = createSession(304L, false);
        String plainToken = invite(plain.id(), "plain@test.dev");
        assertThatThrownBy(() -> envCheck.saveProgress(plainToken, json.createObjectNode())).hasMessageContaining("감독");

        SessionSummaryResponse other = createSession(305L, true);
        assertThatThrownBy(() -> envCheck.view(other.id(), pid)).isInstanceOf(NoSuchElementException.class);

        clock.set(s.endsAt().plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> envCheck.saveProgress(token, json.createObjectNode())).hasMessageContaining("끝난");
    }

    @Test
    @DisplayName("안내 메시지: 보내면 응시자가 받고, 확인하면 콘솔 목록에 읽은 시각이 남는다")
    void sendReadFlow() {
        SessionSummaryResponse s = createSession(310L, true);
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
        SessionSummaryResponse s = createSession(311L, true);
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
        SessionSummaryResponse s = createSession(312L, true);
        String token = invite(s.id(), "nrej@test.dev");
        Long pid = pid(s.id(), "nrej@test.dev");

        assertThatThrownBy(() -> notices.send(s.id(), pid, "   ", 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notices.send(s.id(), pid, "가".repeat(NoticeService.MAX_LENGTH + 1), 7L))
                .isInstanceOf(IllegalArgumentException.class);
        notices.send(s.id(), pid, "가".repeat(NoticeService.MAX_LENGTH), 7L); // 정확히 한도는 허용

        SessionSummaryResponse other = createSession(313L, true);
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

    // ── 환경 점검 사진(귀·손목). 컨텍스트를 늘리지 않으려고 같은 설정의 이 클래스에 둔다 ──

    /** 최소한의 JPEG (FF D8 FF 로 시작) */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] JPEG2 = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 8, 'E', 'x', 'i', 'f', 1, 2};

    @Autowired com.pair.domain.exam.service.EnvCheckPhotoService envPhotos;

    private static com.pair.domain.exam.dto.EnvCheckPhotoRequest photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage stage, byte[] jpeg) {
        return new com.pair.domain.exam.dto.EnvCheckPhotoRequest(stage, "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg));
    }

    @Test
    @DisplayName("점검 사진: 귀·손목을 올리면 콘솔에서 목록과 사진을 볼 수 있고, 다시 올리면 같은 단계를 덮어쓴다")
    void envCheckPhotoUploadViewReplace() {
        SessionSummaryResponse s = createSession(501L, true);
        String token = invite(s.id(), "ph1@test.dev");
        Long pid = pid(s.id(), "ph1@test.dev");

        var left = envPhotos.upload(token, photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.EAR_LEFT, JPEG));
        var wrist = envPhotos.upload(token, photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.WRIST, JPEG));

        assertThat(envPhotos.photosOf(s.id(), pid)).extracting(v -> v.id()).containsExactly(left.id(), wrist.id());
        assertThat(envPhotos.image(s.id(), left.id())).isEqualTo(JPEG);

        var again = envPhotos.upload(token, photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.EAR_LEFT, JPEG2));
        assertThat(again.id()).isEqualTo(left.id()); // 단계마다 한 장
        assertThat(envPhotos.photosOf(s.id(), pid)).hasSize(2);
        assertThat(envPhotos.image(s.id(), left.id())).isEqualTo(JPEG2);
    }

    @Test
    @DisplayName("점검 사진 검증: 잘못된 링크, JPEG 아님, 감독 안 쓰는 회차, 취소·끝난 회차는 거절한다")
    void envCheckPhotoRejects() {
        SessionSummaryResponse s = createSession(502L, true);
        String token = invite(s.id(), "ph2@test.dev");
        var ok = photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.WRIST, JPEG);

        assertThatThrownBy(() -> envPhotos.upload("x".repeat(43), ok)).isInstanceOf(NoSuchElementException.class);
        var png = new com.pair.domain.exam.dto.EnvCheckPhotoRequest(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.WRIST,
                java.util.Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2}));
        assertThatThrownBy(() -> envPhotos.upload(token, png)).hasMessageContaining("JPEG");

        String plainToken = invite(createSession(503L, false).id(), "ph3@test.dev");
        assertThatThrownBy(() -> envPhotos.upload(plainToken, ok)).hasMessageContaining("감독");

        SessionSummaryResponse canceled = createSession(504L, true);
        String canceledToken = invite(canceled.id(), "ph4@test.dev");
        sessions.cancel(canceled.id());
        assertThatThrownBy(() -> envPhotos.upload(canceledToken, ok)).hasMessageContaining("취소");

        clock.set(s.endsAt().plus(Duration.ofMinutes(10)));
        assertThatThrownBy(() -> envPhotos.upload(token, ok)).hasMessageContaining("끝난");
    }

    @Test
    @DisplayName("점검 사진: 다른 회차 번호로는 볼 수 없고, 회차 종료 7일 뒤 사진만 지운다")
    void envCheckPhotoScopeAndPurge() {
        SessionSummaryResponse s = createSession(505L, true);
        SessionSummaryResponse other = createSession(506L, true);
        String token = invite(s.id(), "ph5@test.dev");
        var photo = envPhotos.upload(token, photoReq(com.pair.domain.exam.entity.EnvCheckPhoto.Stage.EAR_RIGHT, JPEG));
        Long pid = pid(s.id(), "ph5@test.dev");

        assertThatThrownBy(() -> envPhotos.image(other.id(), photo.id())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> envPhotos.photosOf(other.id(), pid)).isInstanceOf(NoSuchElementException.class);

        clock.set(s.endsAt().plus(Duration.ofDays(6)));
        assertThat(envPhotos.purgeExpired()).isZero();
        clock.set(s.endsAt().plus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(envPhotos.purgeExpired()).isGreaterThanOrEqualTo(1); // 같은 컨텍스트의 다른 테스트가 남긴 사진도 함께 지워질 수 있다
        assertThatThrownBy(() -> envPhotos.image(s.id(), photo.id())).hasMessageContaining("삭제된 사진");
        assertThat(envPhotos.photosOf(s.id(), pid)).singleElement().extracting(v -> v.deleted()).isEqualTo(true);
    }

    private SessionSummaryResponse createSession(Long company, boolean proctoring) {
        return sessions.create(new ExamSessionRequest(company, "점검 회차", clock.instant().plus(Duration.ofMinutes(10)),
                clock.instant().plus(Duration.ofMinutes(70)), proctoring, List.of(com.pair.domain.task.TestTasks.LIVE_DEV)), 1L);
    }

    private String invite(Long sessionId, String email) {
        sessions.addParticipants(sessionId, List.of(new ParticipantInputDto("응시자", email)));
        Long id = pid(sessionId, email);
        return appEvents.stream(InvitesCreatedDto.class).flatMap(e -> e.invites().stream())
                .filter(i -> i.participantId().equals(id)).reduce((a, b) -> b).orElseThrow().token();
    }

    private Long pid(Long sessionId, String email) {
        return participants.findBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(p -> p.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }
}
