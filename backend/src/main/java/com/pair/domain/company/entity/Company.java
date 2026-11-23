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
}
