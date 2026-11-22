package com.pair.exam.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pair.exam.domain.EnvScanPhoto;
import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.domain.ProctorEvent;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회차 API 요청·응답 DTO 모음. */
public final class ExamDtos {
    private ExamDtos() {}

    // ── 콘솔: 회차 ──

    /** companyId: 관리자만 넣는다. 기업 회원은 비워도 되고, 넣어도 로그인한 회사로 바뀐다 */
    public record SessionRequest(
            Long companyId,
            @NotBlank @Size(max = 100) String title,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            boolean proctoring,
            @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 64) String> taskIds) {

        public SessionRequest withCompany(Long company) {
            return new SessionRequest(company, title, startsAt, endsAt, proctoring, taskIds);
        }
    }

    public record SessionSummary(Long id, Long companyId, String title, Instant startsAt, Instant endsAt, boolean proctoring,
            ExamSession.Phase phase, List<String> taskIds, long participants, long entered, long submitted,
            Instant canceledAt, long unreviewedEvents, Instant createdAt) {}

    /**
     * startedAt·submittedAt·attempts 는 과제별 응시(attempts)에서 계산한다 (ParticipantProgress). attempts: 과제 code → 응시 ID
     * linkExpired: 연결 유예(5분)를 넘겨 입장 링크가 만료된 상태 (진행 중인 시험에서만 true. 콘솔의 "링크 복구" 버튼 조건)
     */
    public record ParticipantView(Long id, String name, String email, ExamParticipant.InviteStatus inviteStatus,
            String inviteError, Instant invitedAt, Instant remindedAt, Instant enteredAt, Instant startedAt,
            Instant submittedAt, Map<String, String> attempts, boolean linkExpired) {

        public static ParticipantView from(ExamParticipant p, com.pair.exam.service.ParticipantProgress.Progress pr, Instant now) {
            boolean live = p.getSession().phase(now) == ExamSession.Phase.LIVE;
            boolean expired = live && com.pair.exam.service.ConnectionGrace.isExpired(p, pr.started(), pr.finished(), now);
            return new ParticipantView(p.getId(), p.getName(), p.getEmail(), p.getInviteStatus(), p.getInviteError(),
                    p.getInvitedAt(), p.getRemindedAt(), p.getEnteredAt(), pr.startedAt(), pr.submittedAt(), pr.attempts(), expired);
        }
    }

    public record SessionDetail(SessionSummary session, Instant serverNow, Instant closedAt,
            List<ParticipantView> participants) {}

    // ── 콘솔: 대상자 ──

    /** 행 단위 검증(형식·길이·중복)은 서비스가 한다. 잘못된 행 하나 때문에 전체를 거절하지 않고 이유와 함께 돌려준다 */
    public record ParticipantInput(String name, String email) {}

    public record AddParticipantsRequest(@NotEmpty @Size(max = 1000) List<@NotNull ParticipantInput> participants) {}

    /** line: CSV 줄 번호(헤더 = 1) 또는 요청 목록 순서(1부터) */
    public record Rejected(int line, String name, String email, String reason) {}

    /** added: 등록한 인원 (미리 보기면 등록될 인원) */
    public record AddParticipantsResult(int added, List<Rejected> rejected) {}

    // ── 응시자: 입장 ──

    /**
     * companyName: 아직 채우지 않는다 (명세상 선택 필드).
     * submittedAt: 모든 과제를 제출한 시각. 과제별 제출 여부는 tasks.
     * linkExpired: 시험 중 연결이 끊긴 뒤 유예(5분) 안에 돌아오지 않아 입장 링크가 만료됨.
     */
    public record EntryInfo(String sessionTitle, String companyName, String name, String email, Instant startsAt, Instant endsAt, Instant serverNow,
            ExamSession.Phase phase, boolean proctoring, boolean consented, Instant startedAt, Instant submittedAt,
            List<String> taskIds, Map<String, String> attempts, List<TaskStatus> tasks, boolean linkExpired) {}

    /** 회차 과제 하나의 진행 상태. attemptId 는 시작 전이면 null */
    public record TaskStatus(String taskId, String attemptId, boolean submitted) {}

    public record ConsentRequest(@NotBlank @Size(max = 20) String consentVersion, @NotNull JsonNode envCheck) {}

    // ── 응시자: 감독 이벤트 ──

    public record ProctorEventRequest(
            @NotNull ProctorEvent.Type type,
            @NotNull ProctorEvent.Source source,
            @NotNull Instant startedAt,
            @Min(0) @Max(3_600_000) int durationMs,
            @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
            JsonNode detail,
            /** data:image/jpeg;base64,... 또는 base64 본문. 카메라 이벤트만 */
            String snapshot,
            /** 브라우저가 이벤트마다 만든 고유값(UUID). 재전송해도 이벤트가 두 건이 되지 않게 한다. 없어도 받는다 */
            @Pattern(regexp = "[A-Za-z0-9-]{8,36}") String clientId) {

        /** clientId 없이 만드는 편의 생성자 (옛 클라이언트·테스트) */
        public ProctorEventRequest(ProctorEvent.Type type, ProctorEvent.Source source, Instant startedAt, int durationMs,
                BigDecimal confidence, JsonNode detail, String snapshot) {
            this(type, source, startedAt, durationMs, confidence, detail, snapshot, null);
        }
    }

    public record ProctorEventView(Long id, Long participantId, ProctorEvent.Type type, ProctorEvent.Source source, Instant startedAt,
            int durationMs, BigDecimal confidence, String detail, String snapshot, int snapshotBytes,
            ProctorEvent.Review review, String reviewNote, Long reviewedBy, Instant reviewedAt,
            /** 사진이 없는 카메라 이벤트의 사유: LIMIT_EXCEEDED(서버 사진 상한 초과) / UPLOAD_FAILED(업로드 실패·사진 없이 도착). 사진이 있거나 삭제됐거나 브라우저 이벤트면 null */
            String snapshotMissing) {

        public static ProctorEventView from(ProctorEvent e) {
            String snap = e.getSnapshotKey() == null ? null : e.getSnapshotDeletedAt() != null ? "deleted" : "ok";
            String missing = null;
            if (e.getSnapshotKey() == null && e.getSource() != ProctorEvent.Source.BROWSER) {
                missing = e.getSnapshotSkipReason() != null ? e.getSnapshotSkipReason().name() : "UPLOAD_FAILED";
            }
            return new ProctorEventView(e.getId(), e.getParticipantId(), e.getType(), e.getSource(), e.getStartedAt(), e.getDurationMs(),
                    e.getConfidence(), e.getDetail(), snap, e.getSnapshotBytes(), e.getReview(), e.getReviewNote(),
                    e.getReviewedBy(), e.getReviewedAt(), missing);
        }
    }

    // ── 응시자: 환경 점검 사진 (방 360도, 책상 위) ──

    public record EnvScanRequest(
            @NotNull EnvScanPhoto.Stage stage,
            /** 방 스캔에서 이 사진을 찍은 방향(0~359도). 책상은 비움 */
            @Min(0) @Max(359) Integer angle,
            /** 사진에서 감지한 물체 이름 */
            @Size(max = 12) List<@Size(max = 30) String> objects,
            /** data:image/jpeg;base64,... 또는 base64 본문 */
            @NotBlank String image) {}

    public record EnvScanPhotoView(Long id, Long participantId, EnvScanPhoto.Stage stage, Integer angle, List<String> objects,
            boolean phoneSuspect, int sizeBytes, Instant capturedAt, boolean deleted) {

        public static EnvScanPhotoView from(EnvScanPhoto f) {
            List<String> labels = f.getObjects() == null || f.getObjects().isBlank() ? List.of() : List.of(f.getObjects().split(","));
            return new EnvScanPhotoView(f.getId(), f.getParticipantId(), f.getStage(), f.getAngleDeg() == null ? null : f.getAngleDeg().intValue(),
                    labels, f.isPhoneSuspect(), f.getSizeBytes(), f.getCapturedAt(), f.getDeletedAt() != null);
        }
    }

    /** 담당자 판정. review 가 null 이면 판정을 지운다. 판정자는 로그인 정보에서 꺼낸다 */
    public record ReviewRequest(ProctorEvent.Review review, @Size(max = 1000) String note) {}

    public record ServerTime(Instant serverNow) {}

    // ── 환경 점검 콘솔 확인 · 안내 메시지 ──

    /** 점검 결과의 출처: LIVE 입장 대기 중 진행 상태, SAVED 동의와 함께 저장된 결과 */
    public enum EnvCheckSource { LIVE, SAVED }

    public record EnvProgressRequest(@NotNull JsonNode result) {}

    /** 콘솔이 보는 환경 점검 결과. result 는 응시자 브라우저가 올린 점검 항목 JSON 그대로 */
    public record EnvCheckView(EnvCheckSource source, Instant updatedAt, JsonNode result) {}

    public record NoticeRequest(@NotBlank @Size(max = 500) String message) {}

    /** 안내 메시지. readAt 이 비어 있으면 응시자가 아직 확인하지 않았다 */
    public record NoticeView(Long id, String message, Instant sentAt, Instant readAt) {

        public static NoticeView from(com.pair.exam.domain.ProctorNotice n) {
            return new NoticeView(n.getId(), n.getMessage(), n.getSentAt(), n.getReadAt());
        }
    }
}
