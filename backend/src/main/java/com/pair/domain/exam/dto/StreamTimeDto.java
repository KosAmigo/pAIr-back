package com.pair.domain.exam.dto;

import java.time.Instant;

/** 상시 저장 사진 한 장의 시각 (그 초의 사진 1장이라 n 은 항상 1) */
public record StreamTimeDto(Instant t, int n) {}
