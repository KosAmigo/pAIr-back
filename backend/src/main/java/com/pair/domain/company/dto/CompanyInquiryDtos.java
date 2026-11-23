package com.pair.domain.company.dto;

import com.pair.domain.company.entity.Company;
import com.pair.domain.company.entity.CompanyInquiry;
import java.time.Instant;
import java.util.List;

import com.pair.global.security.CurrentUser;

public final class CompanyInquiryDtos {
    private CompanyInquiryDtos() {}

    public record MemberInput(String name, String email, CurrentUser.Role role) {}
    public record MemberView(String name, String email, CurrentUser.Role role) {}
    public record InquiryView(long id, String companyName, String businessNo, String contactName, String contactEmail,
            String message, CompanyInquiry.Status status, Long companyId, Instant createdAt, List<MemberView> members) {}
    public record CompanyView(long id, String name, String businessNo, String contact, Instant createdAt) {
        public static CompanyView from(Company c) {
            return new CompanyView(c.getId(), c.getName(), c.getBusinessNo(), c.getContact(), c.getCreatedAt());
        }
    }
}
