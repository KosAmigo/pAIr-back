package com.pair.domain.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoticeRequest(@NotBlank @Size(max = 500) String message) {}
