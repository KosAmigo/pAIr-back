package com.pair.domain.exam.dto;

import java.util.List;

public record ClipResponse(Long eventId, List<ClipFrameDto> frames) {}
