package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;

/** 자동 확인 검사 1개. 검사를 더하거나 빼도 생성기 코드는 그대로 둔다 */
public interface GenCheckService<D> {

    String name();

    GenCheckResultDto run(D draft);
}
