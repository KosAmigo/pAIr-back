package com.pair.domain.taskgen.service;

/** 자동 확인 검사 1개. 검사를 더하거나 빼도 생성기 코드는 그대로 둔다 */
public interface GenCheck<D> {

    String name();

    CheckResult run(D draft);
}
