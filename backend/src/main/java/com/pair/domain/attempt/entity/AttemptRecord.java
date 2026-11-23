package com.pair.domain.attempt.entity;

import com.pair.domain.attempt.repository.AttemptStore;
import com.pair.domain.attempt.repository.DbAttemptStore;
import com.pair.domain.attempt.service.Attempt;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 응시 1건의 목록 정보 (ERD attempts): 누가(자가진단 회원 / 실전 대상자), 어떤 과제를, 어떤 상태로, 언제 시작·제출했는지.
 * 작업 파일(attempt_files)·대화(chat_turns)·작업 기록(attempt_events)·리포트(attempt_reports)는 DbAttemptStore 가 다룬다.
 */
@Entity
@Table(name = "attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    /** 롤백으로 갈라진 응시만: 출발한 체크포인트 (복기 트리의 부모) */
    @Column(name = "forked_from_checkpoint_id")
    private Long forkedFromCheckpointId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "followups_done_at")
    private Instant followupsDoneAt;

    @Column(length = 20)
    private String provider;

    @Column(name = "first_valid_at")
    private Instant firstValidAt;

    @Column(columnDefinition = "text")
    private String error;

    /** 후속 질문 문구 목록 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "followup_questions")
    private String followupQuestions;

    /** 후속 질문 답 목록 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "followup_answers")
    private String followupAnswers;

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

    /** 체크포인트에서 갈라진 자가진단 응시 */
    public static AttemptRecord forkedSelf(Long userId, Long taskId, String model, Long checkpointId, Instant at) {
        AttemptRecord r = self(userId, taskId, model, at);
        r.forkedFromCheckpointId = checkpointId;
        return r;
    }

    public static AttemptRecord live(Long participantId, Long taskId, String model, Instant at) {
        return new AttemptRecord(Mode.LIVE, null, participantId, taskId, model, at);
    }

    /** 응시 상태 전체를 저장한다(AttemptStore.save). questions·answers 는 JSON 문자열 */
    public void apply(Attempt a, String questionsJson, String answersJson) {
        sync(a.status(), a.submittedAt(), a.followupsDoneAt());
        this.provider = a.provider();
        if (a.model() != null) this.assistantModel = a.model();
        if (this.firstValidAt == null) this.firstValidAt = a.firstValidAt();
        this.error = a.error();
        this.followupQuestions = questionsJson;
        this.followupAnswers = answersJson;
    }

    /** 응시 상태를 맞춘다. 제출 시각은 한 번 정해지면 바꾸지 않는다 */
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

}
