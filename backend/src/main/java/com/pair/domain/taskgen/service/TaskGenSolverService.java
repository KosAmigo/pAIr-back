package com.pair.domain.taskgen.service;

/** 다른 회사 모델이 과제를 풀어 보는 호출 (비개발 검사 7). 테스트에서는 가짜로 바꿔 끼운다 */
public interface TaskGenSolverService {

    /** 답변 글과 쓴 토큰 */
    record Solved(String text, long inputTokens, long outputTokens) {}

    /** prompt 에 답하게 하고 답변 글을 돌려준다. 호출이 실패하면 AiUnavailableException */
    String solve(String model, String prompt);

    /** 토큰까지 돌려준다(report 에 기록). 토큰을 모르는 구현은 0 */
    default Solved solveCounted(String model, String prompt) {
        return new Solved(solve(model, prompt), 0, 0);
    }
}
