package com.pair.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 과제 생성 설정 (pair.taskgen.*).
 * model: 생성 모델, solverModel: 비개발 과제를 풀어 보는 다른 회사 모델, maxConcurrent: 동시에 도는 생성 작업 상한, effort: 추론 강도, maxTokens: 출력 토큰 한도.
 * suggestModel/suggestMaxTokens/suggestEffort: 주제·함정 추천(싼 모델, 짧은 출력)용.
 * leakModel/leakMaxTokens: 답 노출 AI 판정(M5)용. leakModel 이 비면 suggestModel 을 쓴다.
 * soloEnabled/soloModel/soloAttempts: 참고 지표 "AI 단독 풀이"(개발 과제, 기본 1회, 기록만). soloModel 이 비면 응시자 AI 모델(pair.assistant.model)과 같다.
 */
@ConfigurationProperties(prefix = "pair.taskgen")
public record TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
        String suggestModel, Integer suggestMaxTokens, String suggestEffort,
        Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens) {

    /** 답 노출 판정 설정 없이 만든다(테스트용): 기본값을 쓴다 */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort,
                soloEnabled, soloModel, soloAttempts, null, null);
    }

    /** 추천 설정 없이 만들 때(테스트용): 추천은 기본값을 쓴다 */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, null, null, null, null, null, null, null, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public TaskGenProperties {
        if (model == null || model.isBlank()) model = "gpt-6.1-terra";
        if (maxConcurrent == null || maxConcurrent < 1) maxConcurrent = 2;
        if (effort == null || effort.isBlank()) effort = "medium";
        if (maxTokens == null || maxTokens < 1000) maxTokens = 48000;
        if (solverModel == null || solverModel.isBlank()) solverModel = "claude-sonnet-5-5";
        if (suggestModel == null || suggestModel.isBlank()) suggestModel = "gpt-6-luna";
        if (suggestMaxTokens == null || suggestMaxTokens < 500) suggestMaxTokens = 2000;
        if (soloEnabled == null) soloEnabled = true;
        if (soloAttempts == null || soloAttempts < 1) soloAttempts = 1;
        if (leakModel == null || leakModel.isBlank()) leakModel = suggestModel;
        if (leakMaxTokens == null || leakMaxTokens < 500) leakMaxTokens = 3000;
        if (suggestEffort == null || suggestEffort.isBlank()) suggestEffort = "low"; // luna 는 minimal 을 받지 않는다
    }
}
