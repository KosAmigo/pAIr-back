package com.pair.company;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_account_events")
public class CompanyAccountEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyAccountEvent() {}

    public CompanyAccountEvent(Long companyId, String eventType, Long actorUserId, Long targetUserId,
            String details, Instant createdAt) {
        this.companyId = companyId;
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.details = details;
        this.createdAt = createdAt;
    }
}
