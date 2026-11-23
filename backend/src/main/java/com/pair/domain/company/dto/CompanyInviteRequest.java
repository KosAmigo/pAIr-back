package com.pair.domain.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyInviteRequest(@NotBlank @Email @Size(max = 255) String email) {}
