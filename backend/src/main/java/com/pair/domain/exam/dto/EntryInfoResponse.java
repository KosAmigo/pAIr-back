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
        List<String> taskIds, Map<String, String> attempts, List<TaskStatusDto> tasks, boolean linkExpired,
        /** 서버 추론 감독을 쓰는 회차인지 (백엔드 스위치·회차 플래그·감독 사용이 모두 참). 거짓이면 기존 PC 분석 경로를 그대로 쓴다 */
        boolean serverVision,
        /** 이 회차가 요구하는 동의 문구 버전 (서버 추론이면 v3, 아니면 v2) */
        String consentVersionRequired) {}
