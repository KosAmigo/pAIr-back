package com.pair.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

class SpringAiRetryTest {

    private static ChatResponse ok() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        ChatResponse r = SpringAi.call("test", () -> {
            if (calls.incrementAndGet() < 3) throw new NonTransientAiException("HTTP 429 - rate limited");
            return ok();
        }, waits::add);
        assertEquals("ok", SpringAi.text(r));
        assertEquals(3, calls.get());
        assertEquals(2, waits.size());
        assertTrue(waits.get(0) >= 2000 && waits.get(0) <= 2500);
        assertTrue(waits.get(1) >= 4000 && waits.get(1) <= 5000);
    }

    @Test
    void givesUpAfterThreeRetries() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(AiUnavailableException.class, () -> SpringAi.call("test", () -> {
            calls.incrementAndGet();
            throw new TransientAiException("503");
        }, w -> { }));
        assertEquals(4, calls.get());
    }

    @Test
    void doesNotRetryAuthError() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(AiUnavailableException.class, () -> SpringAi.call("test", () -> {
            calls.incrementAndGet();
            throw new NonTransientAiException("HTTP 401");
        }, w -> { }));
        assertEquals(1, calls.get());
    }
}
