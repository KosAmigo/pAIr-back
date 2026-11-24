package com.pair.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 과제 생성 설정 (pair.taskgen.*).
 * model: 생성 모델, solverModel: 비개발 과제를 풀어 보는 다른 회사 모델, maxConcurrent: 동시에 도는 생성 작업 상한, effort: 추론 강도, maxTokens: 출력 토큰 한도.
 * suggestModel/suggestMaxTokens/suggestEffort: 주제·함정 추천(싼 모델, 짧은 출력)용.
 * leakModel/leakMaxTokens: 답 노출 AI 판정(M5)용. leakModel 이 비면 suggestModel 을 쓴다.
 * soloEnabled/soloModel/soloAttempts: 참고 지표 "AI 단독 풀이"(개발 과제, 기본 1회, 기록만). soloModel 이 비면 응시자 AI 모델(pair.assistant.model)과 같다.
 * pipeline: staged(기본, 설계 pAIr-docs 설계/과제_생성_파이프라인.md 의 단계별 생성) 또는 oneshot(옛 방식: 한 번에 과제 전체).
 * stages: staged 단계별 모델·추론 강도·출력 한도(blueprint 설계도, writing 작성, code 시작 코드, answer 정답 쪽). 비우면 model 과 아래 기본값.
 */
@ConfigurationProperties(prefix = "pair.taskgen")
public record TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
        String suggestModel, Integer suggestMaxTokens, String suggestEffort,
        Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens,
        String pipeline, Stages stages) {

    /** 단계 하나의 모델·추론 강도·출력 토큰 한도(생각 토큰 포함). 비운 값은 기본값으로 채운다 */
    public record Stage(String model, String effort, Integer maxTokens) {
        Stage filled(String defModel, String defEffort, int defMax) {
            return new Stage(model == null || model.isBlank() ? defModel : model, effort == null || effort.isBlank() ? defEffort : effort,
                    maxTokens == null || maxTokens < 1000 ? defMax : maxTokens);
        }
    }

    /** 설계도(blueprint), 작성(writing), 시작 코드(code), 정답 쪽(answer) */
    public record Stages(Stage blueprint, Stage writing, Stage code, Stage answer) {}

    /** 새 방식(단계별 생성)을 쓰는가 */
    public boolean staged() {
        return !"oneshot".equalsIgnoreCase(pipeline);
    }

    /** 단계 이름(BLUEPRINT, WRITING, CODE, ANSWER)의 설정 */
    public Stage stage(String name) {
        return switch (name) {
            case "BLUEPRINT" -> stages.blueprint();
            case "WRITING" -> stages.writing();
            case "CODE" -> stages.code();
            default -> stages.answer();
        };
    }

    /** 단계 설정 없이 만든다(테스트용): 기본값을 쓴다 */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort,
                soloEnabled, soloModel, soloAttempts, leakModel, leakMaxTokens, null, null);
    }

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
        if (pipeline == null || pipeline.isBlank()) pipeline = "staged";
        Stages s = stages == null ? new Stages(null, null, null, null) : stages;
        stages = new Stages(orEmpty(s.blueprint()).filled(model, "high", 24000), orEmpty(s.writing()).filled(model, "medium", 16000),
                orEmpty(s.code()).filled(model, "medium", 24000), orEmpty(s.answer()).filled(model, "medium", 32000));
    }

    private static Stage orEmpty(Stage s) {
        return s == null ? new Stage(null, null, null) : s;
    }
}
