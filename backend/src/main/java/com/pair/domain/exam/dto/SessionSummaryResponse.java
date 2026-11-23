package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;
import com.pair.domain.exam.entity.ExamSession;

public record SessionSummaryResponse(Long id, Long companyId, String title, Instant startsAt, Instant endsAt, boolean proctoring,
        ExamSession.Phase phase, List<String> taskIds, long participants, long entered, long submitted,
        Instant canceledAt, long unreviewedEvents, Instant createdAt) {}
