package com.pair.domain.exam.dto;

import com.pair.domain.exam.entity.ProctorEvent;

public record ClipFrameDto(Long id, ProctorEvent.Source source, int offsetMs, int sizeBytes, boolean deleted) {}
