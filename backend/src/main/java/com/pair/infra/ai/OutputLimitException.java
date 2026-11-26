package com.pair.infra.ai;

/**
 * 응답 글이 비었고 출력 한도에서 끊겼을 때(finish=length, Claude 는 max_tokens). 생각 토큰이 한도를 다 쓴 경우가 대부분이다.
 * 쓴 토큰은 비용으로 세야 하니 함께 담는다. effortLowered: ModelClient 가 추론 강도를 한 단계 낮춰 다시 불렀는데도 끊긴 것
 */
public class OutputLimitException extends AiUnavailableException {

    private final long inputTokens;
    private final long outputTokens;
    private final boolean effortLowered;

    public OutputLimitException(String message, long inputTokens, long outputTokens, boolean effortLowered) {
        super(message);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.effortLowered = effortLowered;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public boolean effortLowered() {
        return effortLowered;
    }
}
