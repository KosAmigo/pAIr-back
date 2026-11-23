package com.pair.domain.taskgen.service;

/** 과제 생성용 AI 호출. 구조화 출력(JSON Schema)으로 type 하나를 받는다. 테스트에서는 가짜로 바꿔 끼운다 */
public interface TaskGenAiService {

    record Output<T>(T value, long inputTokens, long outputTokens) {}

    /** 호출이 실패하면 AiUnavailableException */
    <T> Output<T> generate(String model, String system, String user, Class<T> type);

    /** 출력 토큰 상한과 추론 강도를 따로 정해 부른다(추천처럼 짧고 싼 호출용). 기본은 위 설정을 그대로 쓴다 */
    default <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
        return generate(model, system, user, type);
    }
}
