package com.pair.domain.company.dto;

import com.pair.global.security.CurrentUser;

public record InquiryMemberInputDto(String name, String email, CurrentUser.Role role) {}
