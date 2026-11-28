package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;
import com.pair.domain.exam.entity.ExamSession;

public record SessionSummaryResponse(Long id, Long companyId, String title, Instant startsAt, Instant endsAt, boolean proctoring,
        ExamSession.Phase phase, List<String> taskIds, long participants, long entered, long submitted,
        Instant canceledAt, long unreviewedEvents, Instant createdAt,
        /** 서버 추론 감독 회차 플래그 (백엔드 스위치가 꺼져 있으면 켜져 있어도 쓰지 않는다) */
        boolean serverVision) {}
