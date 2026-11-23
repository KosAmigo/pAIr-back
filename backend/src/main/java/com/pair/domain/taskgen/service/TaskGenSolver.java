package com.pair.domain.taskgen.service;

/** 다른 회사 모델이 과제를 풀어 보는 호출 (비개발 검사 7). 테스트에서는 가짜로 바꿔 끼운다 */
public interface TaskGenSolver {

    /** prompt 에 답하게 하고 답변 글을 돌려준다. 호출이 실패하면 AiUnavailableException */
    String solve(String model, String prompt);
}
