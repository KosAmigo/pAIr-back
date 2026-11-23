package com.pair.domain.company.dto;

import java.util.List;

public record MemberListResponse(long companyId, List<CompanyMemberResponse> members) {}
