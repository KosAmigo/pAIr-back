package com.pair.company;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_inquiries")
public class CompanyInquiry {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long id;

    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(name = "business_no", nullable = false, length = 20)
    private String businessNo;

    @Column(name = "contact_name", nullable = false, length = 50)
    private String contactName;

    @Column(name = "contact_email", nullable = false, length = 255)
    private String contactEmail;

    @Column(columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyInquiry() {}

    public CompanyInquiry(String companyName, String businessNo, String contactName, String contactEmail,
            String message, Instant createdAt) {
        this.companyName = companyName;
        this.businessNo = businessNo;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.message = message;
        this.createdAt = createdAt;
    }

    public void approve(Long companyId, Long adminId, Instant now) {
        requirePending();
        this.status = Status.APPROVED;
        this.companyId = companyId;
        this.handledBy = adminId;
        this.handledAt = now;
    }

    public void reject(Long adminId, Instant now) {
        requirePending();
        this.status = Status.REJECTED;
        this.handledBy = adminId;
        this.handledAt = now;
    }

    private void requirePending() {
        if (status != Status.PENDING) throw new IllegalStateException("이미 처리된 기업 문의입니다.");
    }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getBusinessNo() { return businessNo; }
    public String getContactName() { return contactName; }
    public String getContactEmail() { return contactEmail; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public Long getCompanyId() { return companyId; }
    public Long getHandledBy() { return handledBy; }
    public Instant getHandledAt() { return handledAt; }
    public Instant getCreatedAt() { return createdAt; }
}
