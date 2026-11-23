package com.pair.domain.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(@NotBlank @Size(max = 43) String token,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{6}") String code) {}
