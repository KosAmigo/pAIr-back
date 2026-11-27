package com.pair.infra.payment;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

@Component
public class TossPaymentClient {
    public record Payment(String paymentKey, String orderId, Integer totalAmount, String status) {}

    /**
     * 승인되지 않은 것이 확실한 오류. 이미 처리됨·처리 중·일시 장애·키 설정 오류는 넣지 않는다.
     * 승인됐을 수 있는 결제를 실패로 바꾸면 돈만 빠지고 크레딧이 안 들어간다.
     */
    private static final Set<String> REJECTED_CODES = Set.of(
            "INVALID_REJECT_CARD", "INVALID_CARD_EXPIRATION", "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN",
            "INVALID_CARD_NUMBER", "INVALID_CARD_INSTALLMENT_PLAN", "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            "NOT_ALLOWED_POINT_USE", "BELOW_MINIMUM_AMOUNT", "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT", "EXCEED_MAX_AMOUNT",
            "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_DAY_AMOUNT",
            "EXCEED_MAX_AUTH_COUNT", "RESTRICTED_TRANSFER_ACCOUNT", "INVALID_ACCOUNT_INFO_RE_REGISTER",
            "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "REJECT_TOSSPAY_INVALID_ACCOUNT",
            "INVALID_PASSWORD", "UNAPPROVED_ORDER_ID", "NOT_FOUND_PAYMENT", "NOT_FOUND_PAYMENT_SESSION");

    /**
     * 취소되지 않은 것이 확실한 결제 취소 오류. 이미 취소됨·처리 중·일시 장애·키 설정 오류는 넣지 않는다
     * (취소됐을 수 있는 결제의 크레딧을 되돌리면 돈과 크레딧을 둘 다 돌려주게 된다).
     */
    private static final Set<String> CANCEL_REJECTED_CODES = Set.of(
            "NOT_CANCELABLE_PAYMENT", "NOT_CANCELABLE_AMOUNT", "FORBIDDEN_REQUEST", "EXCEED_MAX_REFUND_DUE",
            "REFUND_REJECTED", "EXCEED_CANCEL_LIMIT", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER",
            "NOT_ALLOWED_PARTIAL_REFUND", "INVALID_REQUEST", "NOT_FOUND_PAYMENT");

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
            if (e.getStatusCode().is4xxClientError() && REJECTED_CODES.contains(errorCode(e))) {
                throw new PaymentRejectedException("결제가 승인되지 않았습니다. 새 주문으로 다시 시도해 주세요.");
            }
            throw new PaymentUnavailableException("결제 승인 결과를 확인할 수 없습니다. 같은 주문으로 다시 확인해 주세요.", e);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException("결제 승인 결과를 확인할 수 없습니다. 같은 주문으로 다시 확인해 주세요.", e);
        }
    }

    /**
     * 결제를 전액 취소한다(cancelAmount 를 보내지 않으면 전액). 같은 멱등 키로 다시 부르면 토스는 다시 처리하지 않고 첫 응답을 준다(15일).
     * 취소되지 않은 것이 확실하면 PaymentRejectedException, 결과를 모르면 PaymentUnavailableException.
     */
    public Payment cancel(String paymentKey, String cancelReason, String idempotencyKey) {
        requireKey();
        try {
            return client.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .headers(h -> { h.setBasicAuth(secret, ""); h.set("Idempotency-Key", idempotencyKey); })
                    .body(Map.of("cancelReason", cancelReason))
                    .retrieve().body(Payment.class);
        } catch (RestClientResponseException e) {
            JsonNode error = errorBody(e);
            if (e.getStatusCode().is4xxClientError() && CANCEL_REJECTED_CODES.contains(error.path("code").asText())) {
                String reason = error.path("message").asText();
                throw new PaymentRejectedException("토스에서 환불을 거절했습니다" + (reason.isBlank() ? "." : ": " + reason));
            }
            throw new PaymentUnavailableException("환불 결과를 확인할 수 없습니다. 잠시 후 다시 환불을 눌러 확인해 주세요.", e);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException("환불 결과를 확인할 수 없습니다. 잠시 후 다시 환불을 눌러 확인해 주세요.", e);
        }
    }

    /** 본문이 JSON 이 아니면(프록시 오류 화면 등) 빈 값 */
    private static String errorCode(RestClientResponseException e) {
        return errorBody(e).path("code").asText();
    }

    private static JsonNode errorBody(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            return body == null ? MissingNode.getInstance() : body;
        } catch (RuntimeException unreadable) {
            return MissingNode.getInstance();
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
