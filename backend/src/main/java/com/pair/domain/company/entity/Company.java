package com.pair.domain.company.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "business_no", length = 20)
    private String businessNo;

    @Column(length = 50)
    private String contact;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 관리자가 삭제한 시각 (소프트 삭제). 회차·응시 기록이 이 행을 참조하므로 지우지 않는다 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Company() {}

    public Company(String name, String businessNo, String contact, Instant createdAt) {
        this.name = name;
        this.businessNo = businessNo;
        this.contact = contact;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getBusinessNo() { return businessNo; }
    public String getContact() { return contact; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }

    public void delete(Instant at) {
        if (deletedAt != null) throw new IllegalStateException("이미 삭제한 회사입니다.");
        deletedAt = at;
    }
}
