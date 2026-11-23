package com.pair.domain.company.dto;

import com.pair.domain.company.entity.CompanyInquiry;
import java.time.Instant;
import java.util.List;

public record InquiryResponse(long id, String companyName, String businessNo, String contactName, String contactEmail,
        String message, CompanyInquiry.Status status, Long companyId, Instant createdAt, List<InquiryMemberDto> members) {}
