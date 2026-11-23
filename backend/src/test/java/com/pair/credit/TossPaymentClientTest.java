package com.pair.credit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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

    @Test
    void liveKeysAreRejected() {
        assertThatThrownBy(() -> new TossPaymentClient("live_gsk_example", RestClient.builder()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
