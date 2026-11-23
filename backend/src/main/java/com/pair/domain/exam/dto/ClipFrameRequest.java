package com.pair.domain.exam.dto;

import com.pair.domain.exam.entity.ProctorEvent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** offsetMs: 이벤트 시작 기준 상대 시각(-8000 ~ 4000). image: data:image/jpeg;base64,... 또는 base64 본문 */
public record ClipFrameRequest(@NotNull ProctorEvent.Source source, @Min(-8000) @Max(4000) int offsetMs, @NotBlank String image) {}
