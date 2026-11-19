package com.pair.exam.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/** 실전 시험 회차. 시작·종료 시각은 서버 시각 기준이다. */
@Entity
@Table(name = "exam_sessions")
public class ExamSession {

    /** CANCELED: 시작 전에 취소한 회차. 시각과 상관없이 입장·시작이 막힌다 */
    public enum Phase { UPCOMING, LIVE, ENDED, CANCELED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean proctoring;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 시작 전 취소 시각. 회차는 지우지 않고 기록을 남긴다 */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** 마지막으로 올린 대상자 CSV 원본의 저장 위치 */
    @Column(name = "participants_csv_key", length = 255)
    private String participantsCsvKey;

    /** 과제 순서 = 리스트 순서 (exam_session_tasks.sort_order) */
    @ElementCollection
    @CollectionTable(name = "exam_session_tasks", joinColumns = @JoinColumn(name = "session_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "task_id", length = 64, nullable = false)
    private List<String> taskIds = new ArrayList<>();

    protected ExamSession() {}

    public ExamSession(Long companyId, String title, Instant startsAt, Instant endsAt, boolean proctoring,
            List<String> taskIds, Instant createdAt) {
        this.companyId = companyId;
        this.createdAt = createdAt;
        update(title, startsAt, endsAt, proctoring, taskIds);
    }

    public void update(String title, Instant startsAt, Instant endsAt, boolean proctoring, List<String> taskIds) {
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("종료 시각은 시작 시각보다 뒤여야 합니다.");
        if (taskIds == null || taskIds.isEmpty()) throw new IllegalArgumentException("과제를 하나 이상 골라 주세요.");
        if (taskIds.stream().distinct().count() != taskIds.size()) throw new IllegalArgumentException("같은 과제를 두 번 넣을 수 없습니다.");
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.proctoring = proctoring;
        this.taskIds.clear();
        this.taskIds.addAll(taskIds);
    }

    public Phase phase(Instant now) {
        if (canceledAt != null) return Phase.CANCELED;
        if (now.isBefore(startsAt)) return Phase.UPCOMING;
        if (now.isBefore(endsAt)) return Phase.LIVE;
        return Phase.ENDED;
    }

    public void close(Instant at) {
        this.closedAt = at;
    }

    /** 시작 전에만 취소할 수 있다 (DB 에도 canceled_at < starts_at 검사가 있다) */
    public void cancel(Instant at) {
        if (canceledAt != null) throw new IllegalStateException("이미 취소한 회차입니다.");
        if (!at.isBefore(startsAt)) throw new IllegalStateException("시작한 회차는 취소할 수 없습니다.");
        this.canceledAt = at;
    }

    public void recordParticipantsCsv(String key) {
        this.participantsCsvKey = key;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getTitle() { return title; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isProctoring() { return proctoring; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public String getParticipantsCsvKey() { return participantsCsvKey; }
    public List<String> getTaskIds() { return List.copyOf(taskIds); }
}
