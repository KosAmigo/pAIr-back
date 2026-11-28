package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.pair.domain.exam.entity.ExamSession;

/**
 * companyName: 회차를 연 회사 이름 (입장권·대기실·시험 화면 머리에 보인다).
 * submittedAt: 모든 과제를 제출한 시각. 과제별 제출 여부는 tasks.
 * linkExpired: 시험 중 연결이 끊긴 뒤 유예(5분) 안에 돌아오지 않아 입장 링크가 만료됨.
 */
public record EntryInfoResponse(String sessionTitle, String companyName, String name, String email, Instant startsAt, Instant endsAt, Instant serverNow,
        ExamSession.Phase phase, boolean proctoring, boolean consented, Instant startedAt, Instant submittedAt,
        List<String> taskIds, Map<String, String> attempts, List<TaskStatusDto> tasks, boolean linkExpired) {}
