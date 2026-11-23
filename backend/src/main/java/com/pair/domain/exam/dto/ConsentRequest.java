package com.pair.domain.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConsentRequest(@NotBlank @Size(max = 20) String consentVersion, @NotNull JsonNode envCheck) {}
