package com.pair.domain.exam.entity;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

    /** 만든 회원 (같은 회사 MANAGER 또는 관리자) */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

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

    /** 회차 과제 (tasks.task_id 와 순서). 화면에는 TaskCatalog 로 code 를 붙여 보여 준다 */
    @ElementCollection
    @CollectionTable(name = "exam_session_tasks", joinColumns = @JoinColumn(name = "session_id"))
    private Set<SessionTask> tasks = new HashSet<>();

    protected ExamSession() {}

    public ExamSession(Long companyId, String title, Instant startsAt, Instant endsAt, boolean proctoring,
            List<Long> taskIds, Long createdBy, Instant createdAt) {
        this.companyId = companyId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        update(title, startsAt, endsAt, proctoring, taskIds);
    }

    public void update(String title, Instant startsAt, Instant endsAt, boolean proctoring, List<Long> taskIds) {
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("종료 시각은 시작 시각보다 뒤여야 합니다.");
        if (taskIds == null || taskIds.isEmpty()) throw new IllegalArgumentException("과제를 하나 이상 골라 주세요.");
        if (taskIds.stream().distinct().count() != taskIds.size()) throw new IllegalArgumentException("같은 과제를 두 번 넣을 수 없습니다.");
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.proctoring = proctoring;
        if (!taskIds.equals(getTaskIds())) {
            // 바뀐 행만 지우고 넣는다 (Hibernate 는 지우기를 넣기보다 먼저 해서 (회차, 순서) 유일 검사에 걸리지 않는다)
            Set<SessionTask> next = new HashSet<>();
            for (int i = 0; i < taskIds.size(); i++) next.add(new SessionTask(taskIds.get(i), (short) (i + 1)));
            this.tasks.retainAll(next);
            this.tasks.addAll(next);
        }
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
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public String getParticipantsCsvKey() { return participantsCsvKey; }
    /** 회차 과제의 tasks.task_id (순서대로) */
    public List<Long> getTaskIds() {
        return tasks.stream().sorted(Comparator.comparingInt(SessionTask::sortOrder)).map(SessionTask::taskId).toList();
    }
}
