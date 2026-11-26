package com.pair.domain.credit.dto;

/** 크레딧 잔액. unlimited 면(관리자) 잔액과 상관없이 무제한으로 쓰며, 화면은 잔액을 보여 주지 않는다 */
public record BalanceResponse(int balance, boolean unlimited) {}
