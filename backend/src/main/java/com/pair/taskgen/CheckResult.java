package com.pair.taskgen;

/** 검사 1개의 결과. detail 은 통과해도 실패해도 한 줄 설명을 담는다 */
public record CheckResult(String name, boolean ok, String detail) {}
