package com.pair.domain.company.dto;

import java.util.List;

public record CompanyDetailResponse(CompanyResponse company, List<CompanyMemberResponse> members) {}
