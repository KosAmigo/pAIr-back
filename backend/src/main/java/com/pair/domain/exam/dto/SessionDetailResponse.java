package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

public record SessionDetailResponse(SessionSummaryResponse session, Instant serverNow, Instant closedAt,
        List<ParticipantResponse> participants) {}
