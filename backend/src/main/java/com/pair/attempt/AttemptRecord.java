package com.pair.attempt;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 응시 1건의 목록 정보 (ERD attempts): 누가(자가진단 회원 / 실전 대상자), 어떤 과제를, 어떤 상태로, 언제 시작·제출했는지.
 * 작업 파일·대화·채점 리포트는 아직 pilot-data/attempts/{attempt_id}/ 파일에 있다(AttemptStore).
 * 파일의 상태가 바뀌면 AttemptRegistry 가 이 행을 맞춘다.
 */
@Entity
@Table(name = "attempts")
public class AttemptRecord {

    public enum Mode { SELF, LIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_id")
    private Long id;

    /** 자가진단만 (실전은 null) */
    @Column(name = "user_id")
    private Long userId;

    /** 실전만 (자가진단은 null) */
    @Column(name = "participant_id")
    private Long participantId;

    /** tasks.task_id */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Attempt.Status status = Attempt.Status.SOLVING;

    @Column(name = "assistant_model", nullable = false, length = 50)
    private String assistantModel;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "followups_done_at")
    private Instant followupsDoneAt;

    protected AttemptRecord() {}

    private AttemptRecord(Mode mode, Long userId, Long participantId, Long taskId, String assistantModel, Instant startedAt) {
        this.mode = mode;
        this.userId = userId;
        this.participantId = participantId;
        this.taskId = taskId;
        this.assistantModel = assistantModel;
        this.startedAt = startedAt;
    }

    public static AttemptRecord self(Long userId, Long taskId, String model, Instant at) {
        if (userId == null) throw new IllegalArgumentException("자가진단 응시는 회원이 있어야 합니다.");
        return new AttemptRecord(Mode.SELF, userId, null, taskId, model, at);
    }

    public static AttemptRecord live(Long participantId, Long taskId, String model, Instant at) {
        return new AttemptRecord(Mode.LIVE, null, participantId, taskId, model, at);
    }

    /** 파일의 응시 상태와 맞춘다. 제출 시각은 한 번 정해지면 바꾸지 않는다 */
    public void sync(Attempt.Status status, Instant submittedAt, Instant followupsDoneAt) {
        if (this.submittedAt == null && submittedAt != null) this.submittedAt = submittedAt;
        if (this.followupsDoneAt == null && followupsDoneAt != null) this.followupsDoneAt = followupsDoneAt;
        // ERD 검사: 풀이 중이 아니면 제출 시각, 채점 단계면 후속 질문 완료 시각이 있어야 한다
        if (status != Attempt.Status.SOLVING && this.submittedAt == null) return;
        if (status != Attempt.Status.SOLVING && status != Attempt.Status.FOLLOWUP && this.followupsDoneAt == null) return;
        this.status = status;
    }

    public boolean isSubmitted() {
        return submittedAt != null;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getParticipantId() { return participantId; }
    public Long getTaskId() { return taskId; }
    public Mode getMode() { return mode; }
    public Attempt.Status getStatus() { return status; }
    public String getAssistantModel() { return assistantModel; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getFollowupsDoneAt() { return followupsDoneAt; }
}
