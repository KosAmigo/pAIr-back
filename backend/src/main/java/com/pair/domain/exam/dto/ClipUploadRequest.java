package com.pair.domain.exam.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ClipUploadRequest(@NotEmpty @Size(max = 24) List<@jakarta.validation.Valid ClipFrameRequest> frames) {}
