package com.pair.domain.credit.dto;

import jakarta.validation.constraints.Positive;

public record CreatePurchaseRequest(@Positive int credits) {}
