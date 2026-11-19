package com.pair.exam.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
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

    public record ParticipantView(Long id, String name, String email, ExamParticipant.InviteStatus inviteStatus,
            String inviteError, Instant invitedAt, Instant remindedAt, Instant enteredAt, Instant startedAt,
            Instant submittedAt, ExamParticipant.SubmitKind submitKind, Map<String, String> attempts) {

        public static ParticipantView from(ExamParticipant p) {
            return new ParticipantView(p.getId(), p.getName(), p.getEmail(), p.getInviteStatus(), p.getInviteError(),
                    p.getInvitedAt(), p.getRemindedAt(), p.getEnteredAt(), p.getStartedAt(), p.getSubmittedAt(),
                    p.getSubmitKind(), p.getAttempts());
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
     * companyName: 기업 테이블이 아직 없어 null (명세상 선택 필드).
     * submittedAt: 모든 과제를 제출한 시각. 과제별 제출 여부는 tasks.
     */
    public record EntryInfo(String sessionTitle, String companyName, String name, String email, Instant startsAt, Instant endsAt, Instant serverNow,
            ExamSession.Phase phase, boolean proctoring, boolean consented, Instant startedAt, Instant submittedAt,
            List<String> taskIds, Map<String, String> attempts, List<TaskStatus> tasks) {}

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
            String snapshot) {}

    public record ProctorEventView(Long id, Long participantId, ProctorEvent.Type type, ProctorEvent.Source source, Instant startedAt,
            int durationMs, BigDecimal confidence, String detail, String snapshot, int snapshotBytes,
            ProctorEvent.Review review, String reviewNote, Long reviewedBy, Instant reviewedAt) {

        public static ProctorEventView from(ProctorEvent e) {
            String snap = e.getSnapshotKey() == null ? null : e.getSnapshotDeletedAt() != null ? "deleted" : "ok";
            return new ProctorEventView(e.getId(), e.getParticipantId(), e.getType(), e.getSource(), e.getStartedAt(), e.getDurationMs(),
                    e.getConfidence(), e.getDetail(), snap, e.getSnapshotBytes(), e.getReview(), e.getReviewNote(),
                    e.getReviewedBy(), e.getReviewedAt());
        }
    }

    /** 담당자 판정. review 가 null 이면 판정을 지운다. 판정자는 로그인 정보에서 꺼낸다 */
    public record ReviewRequest(ProctorEvent.Review review, @Size(max = 1000) String note) {}

    public record ServerTime(Instant serverNow) {}
}
