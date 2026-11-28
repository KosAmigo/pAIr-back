package com.pair.infra.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentClientTest {
    @Test
    void confirmationUsesTestKeyAndIdempotencyHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient("test_gsk_example", builder);
        String basic = Base64.getEncoder().encodeToString("test_gsk_example:".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + basic))
                .andExpect(header("Idempotency-Key", "stable-key"))
                .andExpect(content().json("{\"paymentKey\":\"pay\",\"orderId\":\"order-123\",\"amount\":4990}"))
                .andRespond(withSuccess("{\"paymentKey\":\"pay\",\"orderId\":\"order-123\","
                        + "\"totalAmount\":4990,\"status\":\"DONE\",\"method\":\"카드\","
                        + "\"card\":{\"issuerCode\":\"01\"}}", MediaType.APPLICATION_JSON));
        assertThat(client.confirm("pay", "order-123", 4990, "stable-key").status()).isEqualTo("DONE");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({ "400,INVALID_REJECT_CARD", "403,REJECT_CARD_COMPANY", "404,NOT_FOUND_PAYMENT_SESSION" })
    void definiteFailuresAreRejected(int status, String code) {
        assertThatThrownBy(() -> confirmWithError(status, "{\"code\":\"" + code + "\"}"))
                .isInstanceOf(PaymentRejectedException.class);
    }

    @ParameterizedTest
    @CsvSource({ "400,ALREADY_PROCESSED_PAYMENT", "400,ALREADY_PROCESSING_REQUEST", "400,PROVIDER_ERROR",
            "401,UNAUTHORIZED_KEY", "500,UNKNOWN_PAYMENT_ERROR" })
    void uncertainFailuresStayRecoverable(int status, String code) {
        assertThatThrownBy(() -> confirmWithError(status, "{\"code\":\"" + code + "\"}"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void unreadableErrorBodyStaysRecoverable() {
        assertThatThrownBy(() -> confirmWithError(400, "<html>")).isInstanceOf(PaymentUnavailableException.class);
    }

    private static void confirmWithError(int status, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build()
                .expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        new TossPaymentClient("test_gsk_example", builder).confirm("pay", "order-123", 4990, "stable-key");
    }

    @Test
    void cancelSendsFullCancelWithReasonAndIdempotencyHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossPaymentClient client = new TossPaymentClient("test_gsk_example", builder);
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-key"))
                // cancelAmount 를 보내지 않으면 전액 취소
                .andExpect(content().json("{\"cancelReason\":\"고객 요청 환불\"}", true))
                .andRespond(withSuccess("{\"paymentKey\":\"pay\",\"orderId\":\"order-123\",\"totalAmount\":4990,"
                        + "\"status\":\"CANCELED\",\"balanceAmount\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.cancel("pay", "고객 요청 환불", "refund-key").status()).isEqualTo("CANCELED");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({ "403,NOT_CANCELABLE_PAYMENT", "403,EXCEED_MAX_REFUND_DUE", "400,REFUND_REJECTED", "404,NOT_FOUND_PAYMENT" })
    void definiteCancelFailuresAreRejectedWithTossMessage(int status, String code) {
        assertThatThrownBy(() -> cancelWithError(status, "{\"code\":\"" + code + "\",\"message\":\"취소할 수 없는 결제입니다.\"}"))
                .isInstanceOf(PaymentRejectedException.class)
                .hasMessage("토스에서 환불을 거절했습니다: 취소할 수 없는 결제입니다.");
    }

    @ParameterizedTest
    @CsvSource({ "400,ALREADY_CANCELED_PAYMENT", "400,PROVIDER_ERROR", "409,IDEMPOTENT_REQUEST_PROCESSING",
            "401,UNAUTHORIZED_KEY", "403,FORBIDDEN_CONSECUTIVE_REQUEST", "500,FAILED_INTERNAL_SYSTEM_PROCESSING" })
    void uncertainCancelFailuresStayRecoverable(int status, String code) {
        assertThatThrownBy(() -> cancelWithError(status, "{\"code\":\"" + code + "\"}"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    private static void cancelWithError(int status, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build()
                .expect(requestTo("https://api.tosspayments.com/v1/payments/pay/cancel"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        new TossPaymentClient("test_gsk_example", builder).cancel("pay", "고객 요청 환불", "refund-key");
    }

    @Test
    void liveKeysAreRejected() {
        assertThatThrownBy(() -> new TossPaymentClient("live_gsk_example", RestClient.builder()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
