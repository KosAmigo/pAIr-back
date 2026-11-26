package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;

/** 감독 중단 구간의 상시 저장 사진 목록. next 가 있으면 다음 요청의 from 으로 쓴다 */
public record StreamListResponse(Long eventId, String channel, Instant from, Instant to, List<StreamTimeDto> frames, Instant next) {}
