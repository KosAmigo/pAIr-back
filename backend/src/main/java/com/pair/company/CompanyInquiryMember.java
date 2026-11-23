package com.pair.company;

import com.pair.web.auth.CurrentUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_inquiry_members")
public class CompanyInquiryMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_member_id")
    private Long id;

    @Column(name = "inquiry_id", nullable = false)
    private Long inquiryId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrentUser.Role role;

    protected CompanyInquiryMember() {}

    public CompanyInquiryMember(Long inquiryId, String name, String email, CurrentUser.Role role) {
        this.inquiryId = inquiryId;
        this.name = name;
        this.email = email;
        this.role = role;
    }

    public Long getId() { return id; }
    public Long getInquiryId() { return inquiryId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public CurrentUser.Role getRole() { return role; }
}
