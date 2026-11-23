package com.pair.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WithdrawalRequest(@NotBlank @Size(max = 72) String currentPassword) {}
