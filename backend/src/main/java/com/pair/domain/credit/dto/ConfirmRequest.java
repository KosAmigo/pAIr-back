package com.pair.domain.credit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ConfirmRequest(@NotBlank @Size(max = 200) String paymentKey, @Positive int amount) {}
