package com.pair.domain.credit.dto;

import java.time.Instant;

/** 결제 승인 한 건을 처리하는 동안 트랜잭션 밖으로 들고 다니는 값 */
public record ConfirmationDto(String orderId, String paymentKey, int amount, String key, Instant startedAt, boolean paid) {}
