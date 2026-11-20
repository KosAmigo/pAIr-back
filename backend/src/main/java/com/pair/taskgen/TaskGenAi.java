package com.pair.taskgen;

/** 과제 생성용 AI 호출. 구조화 출력(JSON Schema)으로 type 하나를 받는다. 테스트에서는 가짜로 바꿔 끼운다 */
public interface TaskGenAi {

    record Output<T>(T value, long inputTokens, long outputTokens) {}

    /** 호출이 실패하면 AiUnavailableException */
    <T> Output<T> generate(String model, String system, String user, Class<T> type);
}
