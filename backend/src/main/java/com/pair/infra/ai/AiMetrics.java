package com.pair.infra.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * AI 호출 지표(Prometheus). Spring Boot 가 만든 레지스트리는 전역 레지스트리({@link Metrics#globalRegistry})에도 붙으므로,
 * 생성자를 바꾸지 않고 어디서든 부를 수 있다. 서버 밖(단위 테스트)에서는 아무 일도 하지 않는다.
 *
 * <p>라벨은 provider(호출 이름)·model·outcome 으로만 한정한다. 사용자·회사 ID 는 값이 무한히 늘어 Prometheus 메모리를 키우므로 쓰지 않는다.
 * 비용은 지표로 내지 않고 토큰만 센다 (단가는 바뀌므로 Grafana 에서 곱한다).
 */
final class AiMetrics {

    private AiMetrics() {}

    /** 호출 한 번의 결과와 걸린 시간. outcome: ok | error */
    static void call(String provider, String model, boolean ok, long nanos) {
        Timer.builder("pair_ai_call_seconds")
                .description("AI 호출 시간")
                .tag("provider", provider)
                .tag("model", model)
                .tag("outcome", ok ? "ok" : "error")
                .register(Metrics.globalRegistry)
                .record(Duration.ofNanos(nanos));
    }

    /** 사용한 토큰 수. direction: in | out */
    static void tokens(String provider, String model, long in, long out) {
        if (in > 0) counter(provider, model, "in").increment(in);
        if (out > 0) counter(provider, model, "out").increment(out);
    }

    private static Counter counter(String provider, String model, String direction) {
        return Counter.builder("pair_ai_tokens_total")
                .description("AI 입력·출력 토큰 합계")
                .tag("provider", provider)
                .tag("model", model)
                .tag("direction", direction)
                .register(Metrics.globalRegistry);
    }
}
