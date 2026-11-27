package com.pair.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 과제 생성 설정 (pair.taskgen.*).
 * model: 생성 모델, solverModel: 비개발 과제를 풀어 보는 다른 회사 모델, maxConcurrent: 동시에 도는 생성 작업 상한, effort: 추론 강도, maxTokens: 출력 토큰 한도.
 * suggestModel/suggestMaxTokens/suggestEffort: 주제·함정 추천(싼 모델, 짧은 출력)용.
 * leakModel/leakMaxTokens: 답 노출 AI 판정(M5)용. leakModel 이 비면 suggestModel 을 쓴다.
 * soloEnabled/soloModel/soloAttempts: 참고 지표 "AI 단독 풀이"(개발 과제, 기본 1회, 기록만). soloModel 이 비면 응시자 AI 모델(pair.assistant.model)과 같다.
 * solverEffort: 풀이 검사(solverModel)의 추론 강도(기본 medium).
 * pipeline: staged(기본, 설계 pAIr-docs 설계/과제_생성_파이프라인.md 의 단계별 생성) 또는 oneshot(옛 방식: 한 번에 과제 전체).
 * stages: staged 단계별 모델·추론 강도·출력 한도. 직군마다 따로(stages.dev.*, stages.biz.*). 비우면 아래 기본값.
 * costCap: 과제 1개를 만드는 데 쓸 수 있는 비용 상한($, 기본 0.6). 다음 다시 만들기가 넘기면 멈추고 검수 전(HIDDEN)으로 저장한다.
 * costCaps: 직군·제한시간별 상한(키 dev, biz-20, biz-40, biz-60; 2026-11-26 사용자 결정, 파이프라인 문서 5장). 키가 없으면 costCap.
 * pricing: 모델별 $/1M 토큰. 비우면 코드 기본값(application.yml 의 pair.pricing 과 같은 값).
 * materialGate: E6·M6(필요한 묶음만 첨부하면 풀리고 자료 전체는 맥락 한도를 넘음)를 단계별 생성에 켠다. 켜면 설계도가 필요한 묶음·관련 적은 자료 목록을 내고,
 * 단계 4'(stages.*.filler, 기본 gpt-6.1-terra)가 관련 적은 자료를 만들며, M6 관문(분량 검사, stages.*.filler-review 가 다른 회사 모델로 답 노출 검사)이 돈다. 비우면 꺼짐(옛 동작).
 */
@ConfigurationProperties(prefix = "pair.taskgen")
public record TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
        String suggestModel, Integer suggestMaxTokens, String suggestEffort,
        Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens,
        String pipeline, Stages stages, String solverEffort, Double costCap, java.util.Map<String, Price> pricing, Boolean materialGate,
        java.util.Map<String, Double> costCaps) {

    /** 직군·제한시간별 상한 없이 만든다(테스트·옛 설정용): 모든 과제에 costCap */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens,
            String pipeline, Stages stages, String solverEffort, Double costCap, java.util.Map<String, Price> pricing, Boolean materialGate) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort, soloEnabled, soloModel, soloAttempts,
                leakModel, leakMaxTokens, pipeline, stages, solverEffort, costCap, pricing, materialGate, null);
    }

    /** 이 과제의 비용 상한: costCaps 의 dev 또는 biz-20·biz-40·biz-60(30분 이하 20, 50분 이하 40, 그 위 60). 키가 없으면 costCap */
    public double costCap(boolean dev, int minutes) {
        String key = dev ? "dev" : "biz-" + (minutes <= 30 ? 20 : minutes <= 50 ? 40 : 60);
        Double v = costCaps == null ? null : costCaps.get(key);
        return v == null || v <= 0 ? costCap : v;
    }

    /** 단계 하나의 모델·추론 강도·출력 토큰 한도(생각 토큰 포함). 비운 값은 기본값으로 채운다 */
    public record Stage(String model, String effort, Integer maxTokens) {
        Stage filled(String defModel, String defEffort, int defMax) {
            return new Stage(model == null || model.isBlank() ? defModel : model, effort == null || effort.isBlank() ? defEffort : effort,
                    maxTokens == null || maxTokens < 1000 ? defMax : maxTokens);
        }
    }

    /** 모델 가격($/1M 토큰) */
    public record Price(double input, double output) {}

    private static final java.util.Map<String, Price> DEFAULT_PRICES = java.util.Map.of(
            "gpt-6-luna", new Price(0.10, 0.50), "gpt-6.1-terra", new Price(1.0, 5.0), "gpt-6.1-sol", new Price(2.0, 10.0),
            "claude-haiku-5-5", new Price(0.10, 0.50), "claude-sonnet-5-5", new Price(2.0, 10.0));

    /**
     * 한 직군의 단계: 설계도(blueprint), 작성(writing), 시작 코드(code, DEV), 문항 글(questions, BIZ), 정답 쪽(answer),
     * 관문 검사(review: 답 노출·정답 키·테스트 근거·다른 풀이·일관성), 답 노출 두 번째 눈(review2). 생성과 검사는 다른 회사 모델이 맡는다
     */
    public record Track(Stage blueprint, Stage writing, Stage code, Stage questions, Stage answer, Stage review, Stage review2, Stage filler, Stage fillerReview) {

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Track {}

        /** 관련 적은 자료 단계 설정 없이 만든다(테스트용): 기본값을 쓴다 */
        public Track(Stage blueprint, Stage writing, Stage code, Stage questions, Stage answer, Stage review, Stage review2) {
            this(blueprint, writing, code, questions, answer, review, review2, null, null);
        }
    }

    public record Stages(Track dev, Track biz) {}

    /** 새 방식(단계별 생성)을 쓰는가 */
    public boolean staged() {
        return !"oneshot".equalsIgnoreCase(pipeline);
    }

    /** 직군(dev 면 DEV, 아니면 BIZ)과 단계 이름(BLUEPRINT, WRITING, CODE, ...)의 설정 */
    public Stage stage(boolean dev, String name) {
        Track t = dev ? stages.dev() : stages.biz();
        return switch (name) {
            case "BLUEPRINT" -> t.blueprint();
            case "WRITING" -> t.writing();
            case "CODE" -> t.code();
            case "QUESTIONS" -> t.questions();
            case "REVIEW" -> t.review();
            case "REVIEW2" -> t.review2();
            case "FILLER" -> t.filler();
            case "FILLER_REVIEW" -> t.fillerReview();
            default -> t.answer();
        };
    }

    /** 호출 1번의 비용($). 가격을 모르는 모델은 0 */
    public double cost(String model, long inputTokens, long outputTokens) {
        Price p = pricing.get(model);
        return p == null ? 0 : (inputTokens * p.input() + outputTokens * p.output()) / 1_000_000.0;
    }

    /** 단계 설정 없이 만든다(테스트용): 기본값을 쓴다 */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort,
                soloEnabled, soloModel, soloAttempts, leakModel, leakMaxTokens, null, null, null, null, null, null);
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

    /** 비용 설정 없이 만든다(테스트용) */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens,
            String pipeline, Stages stages, String solverEffort) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort,
                soloEnabled, soloModel, soloAttempts, leakModel, leakMaxTokens, pipeline, stages, solverEffort, null, null, null);
    }

    /** 관련 적은 자료 관문(materialGate) 없이 만든다(테스트용): 꺼짐 */
    public TaskGenProperties(String model, Integer maxConcurrent, String effort, Integer maxTokens, String solverModel,
            String suggestModel, Integer suggestMaxTokens, String suggestEffort,
            Boolean soloEnabled, String soloModel, Integer soloAttempts, String leakModel, Integer leakMaxTokens,
            String pipeline, Stages stages, String solverEffort, Double costCap, java.util.Map<String, Price> pricing) {
        this(model, maxConcurrent, effort, maxTokens, solverModel, suggestModel, suggestMaxTokens, suggestEffort,
                soloEnabled, soloModel, soloAttempts, leakModel, leakMaxTokens, pipeline, stages, solverEffort, costCap, pricing, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public TaskGenProperties {
        if (model == null || model.isBlank()) model = "gpt-6.1-terra";
        if (maxConcurrent == null || maxConcurrent < 1) maxConcurrent = 2;
        if (effort == null || effort.isBlank()) effort = "medium";
        if (maxTokens == null || maxTokens < 1000) maxTokens = 48000;
        if (solverModel == null || solverModel.isBlank()) solverModel = "claude-sonnet-5-5"; // 비개발 직접 풀이 검사: 생성(sol)과 다른 회사
        if (suggestModel == null || suggestModel.isBlank()) suggestModel = "gpt-6-luna";
        if (suggestMaxTokens == null || suggestMaxTokens < 500) suggestMaxTokens = 2000;
        if (soloEnabled == null) soloEnabled = true;
        if (soloAttempts == null || soloAttempts < 1) soloAttempts = 1;
        if (leakModel == null || leakModel.isBlank()) leakModel = suggestModel;
        if (leakMaxTokens == null || leakMaxTokens < 500) leakMaxTokens = 3000;
        if (suggestEffort == null || suggestEffort.isBlank()) suggestEffort = "low"; // luna 는 minimal 을 받지 않는다
        if (pipeline == null || pipeline.isBlank()) pipeline = "staged";
        if (solverEffort == null || solverEffort.isBlank()) solverEffort = "medium";
        if (costCap == null || costCap <= 0) costCap = 0.6;
        if (materialGate == null) materialGate = false;
        pricing = pricing == null || pricing.isEmpty() ? DEFAULT_PRICES : pricing;
        Stages s = stages == null ? new Stages(null, null) : stages;
        final String sonnet = "claude-sonnet-5-5", sol = "gpt-6.1-sol", terra = "gpt-6.1-terra";
        Track d = s.dev() == null ? new Track(null, null, null, null, null, null, null, null, null) : s.dev();
        Track b = s.biz() == null ? new Track(null, null, null, null, null, null, null, null, null) : s.biz();
        // 생성은 모두 sol. 검사: DEV 는 sol(관문 평가에서 오탐이 적음)과 Sonnet(답 노출 2차), BIZ 는 Sonnet 과 terra(답 노출 2차)
        stages = new Stages(
                new Track(orEmpty(d.blueprint()).filled(sol, "medium", 24000), orEmpty(d.writing()).filled(sol, "medium", 16000),
                        orEmpty(d.code()).filled(sol, "medium", 24000), orEmpty(d.questions()).filled(sol, "medium", 8000),
                        orEmpty(d.answer()).filled(sol, "medium", 32000), orEmpty(d.review()).filled(sol, "medium", 16000),
                        orEmpty(d.review2()).filled(sonnet, "medium", 16000),
                        orEmpty(d.filler()).filled(terra, "low", 24000), orEmpty(d.fillerReview()).filled(sonnet, "low", 6000)),
                new Track(orEmpty(b.blueprint()).filled(sol, "medium", 24000), orEmpty(b.writing()).filled(sol, "medium", 16000),
                        orEmpty(b.code()).filled(sonnet, "medium", 24000), orEmpty(b.questions()).filled(sol, "medium", 8000),
                        orEmpty(b.answer()).filled(sol, "medium", 32000), orEmpty(b.review()).filled(sonnet, "medium", 16000),
                        orEmpty(b.review2()).filled(terra, "medium", 16000),
                        orEmpty(b.filler()).filled(terra, "low", 24000), orEmpty(b.fillerReview()).filled(sonnet, "low", 6000)));
    }

    private static Stage orEmpty(Stage s) {
        return s == null ? new Stage(null, null, null) : s;
    }
}
