package com.pair.infra.ai;

import com.pair.global.config.PairProperties;

/**
 * 채점 모델 하나(이중 채점의 한 쪽). 호출은 모두 ModelClient 를 지나가므로 회사는 모델 이름(claude-*, gpt-*)으로 정해진다.
 * label 은 화면·기록에 쓰는 이름("claude", "openai")일 뿐 호출 방법과 상관없다. 프롬프트 앞부분(cachedPrefix)은 프롬프트 캐시로 재사용한다.
 */
public record ModelJudge(String label, PairProperties.Model cfg, ModelClient client) implements Judge {

    @Override
    public String name() {
        return label;
    }

    @Override
    public String model() {
        return cfg.model();
    }

    @Override
    public int calls() {
        return cfg.callsOrOne();
    }

    @Override
    public <T> Result<T> judge(String system, String cachedPrefix, String user, Class<T> type) {
        ModelClient.Result<T> r = client.json(cfg.model(), system, cachedPrefix, user, type, (int) cfg.maxTokens(), cfg.effort());
        return new Result<>(r.value(), r.inputTokens(), r.outputTokens(), r.cachedInputTokens());
    }
}
