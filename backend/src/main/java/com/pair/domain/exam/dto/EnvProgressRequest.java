package com.pair.domain.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record EnvProgressRequest(@NotNull JsonNode result) {}
