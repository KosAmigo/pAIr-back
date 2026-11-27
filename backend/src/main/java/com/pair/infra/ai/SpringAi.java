package com.pair.infra.ai;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClient;

/**
 * Spring AI 모델 생성과 공통 처리.
 * Spring AI 자동 설정은 기본 temperature(OpenAI 0.7, Anthropic 0.8)를 넣는데, 최신 추론 모델은 temperature 를 받지 않는다.
 * 그래서 모델은 빌더로 직접 만들고 temperature 는 비워 둔다(모델 기본값 사용). 모델 이름과 옵션은 호출마다 넘긴다.
 */
final class SpringAi {

    private SpringAi() {
    }

    static OpenAiChatModel openAi(String apiKey) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().apiKey(apiKey).build())
                .defaultOptions(OpenAiChatOptions.builder().build())
                .build();
    }

    /** effort: 추론 강도(low/medium/high/...). Spring AI 1.1 에 옵션이 없어 요청에 직접 넣는다 (AnthropicEffortInterceptor). */
    static AnthropicChatModel anthropic(String apiKey, String effort) {
        RestClient.Builder rest = RestClient.builder().requestInterceptor(new AnthropicEffortInterceptor(effort));
        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder().apiKey(apiKey).restClientBuilder(rest).build())
                .defaultOptions(AnthropicChatOptions.builder().build())
                .build();
    }

    /** 호출 시간·성공 여부·토큰을 지표로 남긴다. 어떤 예외든 시간은 error 로 기록하고 그대로 다시 던진다. */
    private static ChatResponse measured(String provider, Supplier<ChatResponse> call, long started) {
        boolean ok = false;
        String model = "unknown";
        try {
            ChatResponse res = call.get();
            if (res == null) throw new AiUnavailableException(provider + " 응답이 비어 있습니다.");
            var meta = res.getMetadata();
            if (meta != null && meta.getModel() != null && !meta.getModel().isBlank()) model = meta.getModel();
            if (meta != null && meta.getUsage() != null) {
                AiMetrics.tokens(provider, model, n(meta.getUsage().getPromptTokens()), n(meta.getUsage().getCompletionTokens()));
            }
            ok = true;
            return res;
        } finally {
            AiMetrics.call(provider, model, ok, System.nanoTime() - started);
        }
    }

    /** 스트리밍 중 난 예외를 화면에 보여 줄 수 있는 예외로 바꾼다. */
    static AiUnavailableException unavailable(String provider, RuntimeException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("401")) return new AiUnavailableException(provider + " API 키가 올바르지 않습니다 (401).");
        if (m.contains("429")) return new AiUnavailableException(provider + " 요청 한도에 걸렸습니다 (429). 잠시 후 다시 시도하세요.");
        return new AiUnavailableException(provider + " 호출 실패: " + shorten(m));
    }

    /** 429·일시 오류를 만나면 기다렸다가 다시 부르는 횟수(첫 호출 제외)와 첫 대기(밀리초). 대기는 2배씩 늘고 조금씩 흔들린다 */
    static final int MAX_RETRIES = 3;
    static final long BACKOFF_MILLIS = 2000;

    /** 호출하고, Spring AI 예외를 화면에 보여 줄 수 있는 AiUnavailableException 으로 바꾼다. 429·과부하·일시 오류는 최대 3번 다시 부른다. */
    static ChatResponse call(String provider, Supplier<ChatResponse> call) {
        return call(provider, call, SpringAi::sleep);
    }

    /** 테스트용: 기다리는 방법(밀리초)을 넘긴다 */
    static ChatResponse call(String provider, Supplier<ChatResponse> call, java.util.function.LongConsumer sleeper) {
        for (int attempt = 0; ; attempt++) {
            try {
                return once(provider, call);
            } catch (RuntimeException e) {
                if (attempt >= MAX_RETRIES || !retryable(e)) throw convert(provider, e);
                long wait = BACKOFF_MILLIS << attempt;
                sleeper.accept(wait + java.util.concurrent.ThreadLocalRandom.current().nextLong(wait / 4 + 1));
            }
        }
    }

    /** 다시 불러 볼 만한 오류: 일시 오류(TransientAiException), 429, 과부하(529·overloaded), 5xx */
    static boolean retryable(RuntimeException e) {
        if (e instanceof TransientAiException) return true;
        if (!(e instanceof NonTransientAiException)) return false;
        String m = String.valueOf(e.getMessage());
        return m.contains("429") || m.contains("529") || m.toLowerCase().contains("overloaded") || m.contains("rate limit")
                || m.contains(" 500") || m.contains(" 502") || m.contains(" 503") || m.contains(" 504");
    }

    private static ChatResponse once(String provider, Supplier<ChatResponse> call) {
        return measured(provider, call, System.nanoTime());
    }

    private static RuntimeException convert(String provider, RuntimeException e) {
        if (e instanceof NonTransientAiException) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("401")) return new AiUnavailableException(provider + " API 키가 올바르지 않습니다 (401).");
            if (m.contains("429")) return new AiUnavailableException(provider + " 요청 한도에 걸렸습니다 (429). 잠시 후 다시 시도하세요.");
            return new AiUnavailableException(provider + " 호출 실패: " + shorten(m));
        }
        if (e instanceof TransientAiException) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("429")) return new AiUnavailableException(provider + " 요청 한도에 걸렸습니다 (429). 잠시 후 다시 시도하세요.");
            return new AiUnavailableException(provider + " 호출 실패(일시 오류): " + shorten(m));
        }
        return e;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("호출을 기다리다 중단됐습니다.");
        }
    }

    /**
     * 답변 본문. Claude 최신 모델은 생각(thinking) 블록을 먼저 보내는데, Spring AI 1.1 은 블록마다 결과(Generation)를 하나씩 만든다.
     * 생각 블록은 글이 비어 있으므로(표시 생략이 기본) 글이 있는 마지막 결과를 쓴다.
     */
    static String text(ChatResponse res) {
        Generation g = answer(res);
        if (g == null || g.getOutput() == null || g.getOutput().getText() == null) return "";
        return g.getOutput().getText();
    }

    static String finishReason(ChatResponse res) {
        Generation g = answer(res);
        if (g == null || g.getMetadata() == null) return "none";
        String f = g.getMetadata().getFinishReason();
        return f == null ? "none" : f.toLowerCase();
    }

    private static Generation answer(ChatResponse res) {
        List<Generation> all = res.getResults();
        if (all == null || all.isEmpty()) return null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Generation g = all.get(i);
            if (g.getOutput() != null && g.getOutput().getText() != null && !g.getOutput().getText().isBlank()) return g;
        }
        return all.get(all.size() - 1);
    }

    static long n(Integer v) {
        return v == null ? 0 : v;
    }

    private static String shorten(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
