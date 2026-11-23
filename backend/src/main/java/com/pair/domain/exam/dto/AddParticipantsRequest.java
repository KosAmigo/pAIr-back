package com.pair.domain.exam.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddParticipantsRequest(@NotEmpty @Size(max = 1000) List<@NotNull ParticipantInputDto> participants) {}
