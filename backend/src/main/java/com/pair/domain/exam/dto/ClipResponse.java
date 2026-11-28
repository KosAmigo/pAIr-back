package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

/**
 * status: READY(승격 끝·사진 있음) / PENDING(승격 전, availableAt 이후에 다시 열면 됨, frames 는 빈 배열) /
 * EXPIRED(보관 기간이 지나 사진이 없음) / NONE(사진을 만들 수 없음: 서버 추론이 아닌 회차의 이벤트·감독 중단 이벤트·묶음 유실)
 */
public record ClipResponse(Long eventId, String status, Instant availableAt, List<ClipFrameDto> frames) {}
