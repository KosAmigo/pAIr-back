package com.pair.infra.payment;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class TossPaymentClient {
    public record Payment(String paymentKey, String orderId, Integer totalAmount, String status) {}

    private final String secret;
    private final RestClient client;

    @Autowired
    public TossPaymentClient(@Value("${pair.toss.secret-key:}") String secret) {
        this(secret, clientBuilder());
    }

    TossPaymentClient(String secret, RestClient.Builder builder) {
        this.secret = secret;
        if (!secret.isBlank() && !secret.startsWith("test_")) {
            throw new IllegalArgumentException("토스 테스트 시크릿 키만 사용할 수 있습니다.");
        }
        this.client = builder.baseUrl("https://api.tosspayments.com").build();
    }

    private static RestClient.Builder clientBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory);
    }

    public Payment confirm(String paymentKey, String orderId, int amount, String idempotencyKey) {
        requireKey();
        try {
            return client.post().uri("/v1/payments/confirm")
                    .headers(h -> { h.setBasicAuth(secret, ""); h.set("Idempotency-Key", idempotencyKey); })
                    .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                    .retrieve().body(Payment.class);
        } catch (RestClientResponseException e) {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (e.getStatusCode().value() == 400 && body != null
                    && "INVALID_REJECT_CARD".equals(body.path("code").asText())) {
                throw new PaymentRejectedException("카드사에서 결제 승인을 거절했습니다.");
            }
            throw new PaymentUnavailableException("결제 승인 결과를 확인할 수 없습니다. 같은 주문으로 다시 확인해 주세요.", e);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException("결제 승인 결과를 확인할 수 없습니다. 같은 주문으로 다시 확인해 주세요.", e);
        }
    }

    public Payment find(String paymentKey) {
        requireKey();
        try {
            return client.get().uri("/v1/payments/{paymentKey}", paymentKey)
                    .headers(h -> h.setBasicAuth(secret, ""))
                    .retrieve().body(Payment.class);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException("토스 결제 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    public void requireKey() {
        if (secret.isBlank()) throw new PaymentUnavailableException("토스 테스트 시크릿 키가 설정되지 않았습니다.");
    }
}
