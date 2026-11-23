package com.pair.domain.company.dto;

import com.pair.domain.company.entity.Company;
import java.time.Instant;

public record CompanyResponse(long id, String name, String businessNo, String contact, Instant createdAt) {
    public static CompanyResponse from(Company c) {
        return new CompanyResponse(c.getId(), c.getName(), c.getBusinessNo(), c.getContact(), c.getCreatedAt());
    }
}
