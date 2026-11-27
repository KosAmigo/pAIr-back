package com.pair.domain.credit.dto;

/** 환불(토스 결제 취소) 한 건을 처리하는 동안 트랜잭션 밖으로 들고 다니는 값. done 이면 이미 환불이 끝난 주문이다 */
public record RefundDto(String orderId, String paymentKey, String key, boolean done) {}
