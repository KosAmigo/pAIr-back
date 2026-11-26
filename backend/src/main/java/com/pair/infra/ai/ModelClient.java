package com.pair.infra.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import com.pair.domain.taskgen.service.TaskGenAiService;
import com.pair.domain.taskgen.service.TaskGenSolverService;
import com.pair.global.config.AiKeyProperties;
import com.pair.global.config.TaskGenProperties;

/**
 * 모델 호출의 단일 입구. 호출하는 쪽은 모델 이름, 프롬프트, 출력 한도, 추론 강도만 넘기고 회사(OpenAI, Anthropic)는 몰라도 된다.
 * 모델 이름이 claude-* 면 Anthropic, gpt-* 면 OpenAI, 그 밖은 오류. 구조화 출력(JSON Schema), 추론 강도 넣는 방법(OpenAI reasoning_effort,
 * Anthropic AnthropicEffortInterceptor), 키가 없을 때의 AiUnavailableException, 토큰 세기는 모두 이 클래스 안에서 끝낸다.
 * 회사를 하나 더하려면 Backend 구현과 vendorOf 한 줄만 고친다.
 * 과제 생성기와 풀이 검사가 쓰는 도메인 인터페이스(TaskGenAiService, TaskGenSolverService)도 이 클래스가 구현해 테스트에서 가짜로 바꿔 끼운다.
 * 스트리밍·배치 호출은 모양이 달라 여기에 넣지 않는다(AssistantClient, BatchClient).
 */
@Component
public class ModelClient implements TaskGenAiService, TaskGenSolverService {

    /** cachedInputTokens: 입력 중 캐시에서 읽은 토큰(inputTokens 에 포함) */
    public record Result<T>(T value, long inputTokens, long outputTokens, long cachedInputTokens) {}

    public record TextResult(String text, long inputTokens, long outputTokens, long cachedInputTokens) {}

    /** 회사별 구현. 모델 이름으로 하나가 골라진다 */
    interface Backend {
        <T> Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort);

        TextResult text(String model, String system, String user, int maxTokens, String effort);
    }

    private static final int SOLVER_MAX_TOKENS = 16000;

    private final TaskGenProperties cfg;
    private final Backend openai;
    private final Backend anthropic;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelClient(TaskGenProperties cfg, AiKeyProperties keys) {
        this(cfg, new OpenAiBackend(keys), new AnthropicBackend(keys));
    }

    /** 테스트용: 회사별 구현을 직접 넘긴다 */
    ModelClient(TaskGenProperties cfg, Backend openai, Backend anthropic) {
        this.cfg = cfg;
        this.openai = openai;
        this.anthropic = anthropic;
    }

    // ── 입구 ─────────────────────────────

    /** 구조화 출력(type 의 JSON Schema)으로 받는다. effort 가 비면 추론 강도를 보내지 않는다 */
    public <T> Result<T> json(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
        return json(model, system, null, user, type, maxTokens, effort);
    }

    /** cachedPrefix: 바뀌지 않는 앞부분(과제, 정답 등). 프롬프트 캐시를 지원하는 회사에서는 재사용된다 */
    public <T> Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort) {
        return backend(model).json(model, system, cachedPrefix, user, type, maxTokens, effort);
    }

    /** 글로 받는다. system 은 없어도 된다 */
    public TextResult text(String model, String system, String user, int maxTokens, String effort) {
        return backend(model).text(model, system, user, maxTokens, effort);
    }

    private Backend backend(String model) {
        return switch (vendorOf(model)) {
            case "anthropic" -> anthropic;
            default -> openai;
        };
    }

    /** 모델 이름으로 회사를 고른다: claude-* 는 anthropic, gpt-* 는 openai, 그 밖은 AiUnavailableException */
    static String vendorOf(String model) {
        String m = model == null ? "" : model.strip().toLowerCase();
        if (m.startsWith("claude-")) return "anthropic";
        if (m.startsWith("gpt-")) return "openai";
        throw new AiUnavailableException("모르는 모델 이름입니다: " + model + " (claude-* 또는 gpt-* 여야 합니다)");
    }

    // ── 도메인 인터페이스 ─────────────────────────────

    @Override
    public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
        return generate(model, system, user, type, cfg.maxTokens(), cfg.effort());
    }

    @Override
    public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
        Result<T> r = json(model, system, user, type, maxTokens, effort);
        return new Output<>(r.value(), r.inputTokens(), r.outputTokens());
    }

    /** 풀이 검사: 추론 강도는 pair.taskgen.solver-effort */
    @Override
    public String solve(String model, String prompt) {
        return solveCounted(model, prompt).text();
    }

    @Override
    public Solved solveCounted(String model, String prompt) {
        TextResult r = text(model, null, prompt, SOLVER_MAX_TOKENS, cfg.solverEffort());
        return new Solved(r.text(), r.inputTokens(), r.outputTokens());
    }

    // ── 공통 도구 ─────────────────────────────

    /** 입력·출력 토큰과 캐시에서 읽은 입력 토큰. 네이티브 사용량(캐시 포함)이 없으면 Spring AI 공통 값 */
    static long[] usageOf(ChatResponse res) {
        var usage = res.getMetadata().getUsage();
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage u) {
            long cached = u.promptTokensDetails() != null ? SpringAi.n(u.promptTokensDetails().cachedTokens()) : 0;
            return new long[] {SpringAi.n(u.promptTokens()), SpringAi.n(u.completionTokens()), cached};
        }
        if (usage.getNativeUsage() instanceof AnthropicApi.Usage u) {
            // Anthropic 의 input_tokens 는 캐시 부분을 뺀 나머지라서 합쳐서 전체 입력으로 센다
            long read = SpringAi.n(u.cacheReadInputTokens());
            return new long[] {SpringAi.n(u.inputTokens()) + read + SpringAi.n(u.cacheCreationInputTokens()), SpringAi.n(u.outputTokens()), read};
        }
        return new long[] {SpringAi.n(usage.getPromptTokens()), SpringAi.n(usage.getCompletionTokens()), 0};
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    // ── OpenAI ─────────────────────────────

    static final class OpenAiBackend implements Backend {
        private final AiKeyProperties keys;
        private volatile OpenAiChatModel chatModel;

        OpenAiBackend(AiKeyProperties keys) {
            this.keys = keys;
        }

        private OpenAiChatModel chatModel() {
            if (chatModel == null) {
                synchronized (this) {
                    if (chatModel == null) {
                        if (!keys.hasOpenai()) {
                            throw new AiUnavailableException("OpenAI API 키(spring.ai.openai.api-key, 환경 변수 OPENAI_API_KEY)가 없어 OpenAI 모델을 부를 수 없습니다.");
                        }
                        chatModel = SpringAi.openAi(keys.openai());
                    }
                }
            }
            return chatModel;
        }

        private static OpenAiChatOptions.Builder options(String model, int maxTokens, String effort) {
            OpenAiChatOptions.Builder o = OpenAiChatOptions.builder().model(model).maxCompletionTokens(maxTokens);
            if (has(effort)) o.reasoningEffort(effort);
            return o;
        }

        @Override
        public <T> Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort) {
            OpenAiChatModel chat = chatModel();
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
            Prompt prompt = new Prompt(messages(system, cachedPrefix, user), options(model, maxTokens, effort)
                    .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema())).build());
            ChatResponse res = SpringAi.call("OpenAI " + model, () -> chat.call(prompt));
            String text = SpringAi.text(res);
            if (text.isBlank()) {
                throw new AiUnavailableException("OpenAI 응답이 비어 있습니다 (finish=" + SpringAi.finishReason(res) + "). 출력 한도를 늘리거나 추론 강도를 낮추세요.");
            }
            T value;
            try {
                value = converter.convert(text);
            } catch (RuntimeException e) {
                throw new AiUnavailableException("OpenAI 결과를 읽지 못했습니다: " + e.getMessage());
            }
            if (value == null) throw new AiUnavailableException("OpenAI 결과가 비어 있습니다.");
            long[] u = usageOf(res);
            return new Result<>(value, u[0], u[1], u[2]);
        }

        @Override
        public TextResult text(String model, String system, String user, int maxTokens, String effort) {
            OpenAiChatModel chat = chatModel();
            Prompt prompt = new Prompt(messages(system, null, user), options(model, maxTokens, effort).build());
            ChatResponse res = SpringAi.call("OpenAI " + model, () -> chat.call(prompt));
            String text = SpringAi.text(res);
            if (text.isBlank()) throw new AiUnavailableException("OpenAI 응답이 비어 있습니다 (finish=" + SpringAi.finishReason(res) + ").");
            long[] u = usageOf(res);
            return new TextResult(text, u[0], u[1], u[2]);
        }
    }

    // ── Anthropic ─────────────────────────────

    static final class AnthropicBackend implements Backend {
        /** 이보다 큰 스키마는 엄격 모드(문법으로 출력을 강제)를 쓰지 않는다. Anthropic 이 "compiled grammar is too large"(400)로 거절한다 */
        static final int STRICT_MAX_SCHEMA_CHARS = 4000;
        private static final String GRAMMAR_TOO_LARGE = "grammar is too large";

        private final AiKeyProperties keys;
        /** 추론 강도는 요청 본문에 직접 넣는 인터셉터(AnthropicEffortInterceptor)라 강도마다 모델을 하나씩 둔다 */
        private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
        private final java.util.function.Function<String, ChatModel> factory;

        AnthropicBackend(AiKeyProperties keys) {
            this(keys, e -> SpringAi.anthropic(keys.anthropic(), e));
        }

        /** 테스트용: 모델 만드는 방법을 넘긴다 */
        AnthropicBackend(AiKeyProperties keys, java.util.function.Function<String, ChatModel> factory) {
            this.keys = keys;
            this.factory = factory;
        }

        private ChatModel chatModel(String effort) {
            if (!keys.hasAnthropic()) {
                throw new AiUnavailableException("Anthropic API 키(spring.ai.anthropic.api-key, 환경 변수 ANTHROPIC_API_KEY)가 없어 Claude 모델을 부를 수 없습니다.");
            }
            String key = !has(effort) || effort.equalsIgnoreCase("none") ? "" : effort.toLowerCase();
            return models.computeIfAbsent(key, factory);
        }

        /** 결과를 읽지 못한 경우(다시 부르면 나아질 수 있음) */
        private static final class ParseFailure extends RuntimeException {
            ParseFailure(String m) {
                super(m);
            }
        }

        /**
         * 스키마가 작으면 엄격 모드(outputSchema), 크면 엄격 모드 없이 스키마를 안내 글로만 보내고 코드에서 읽는다.
         * 엄격 모드가 "문법이 너무 크다"고 거절하면 엄격 모드 없이 다시 부른다. 읽지 못하면 1번 더 부른다. 토큰은 모든 호출을 합친다
         */
        @Override
        public <T> Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort) {
            ChatModel chat = chatModel(effort);
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
            boolean strict = converter.getJsonSchema().length() <= STRICT_MAX_SCHEMA_CHARS;
            long[] sum = new long[3];
            ParseFailure last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    return one(chat, model, system, cachedPrefix, user, converter, strict, maxTokens, sum);
                } catch (ParseFailure e) {
                    last = e;
                } catch (AiUnavailableException e) {
                    if (!strict || !String.valueOf(e.getMessage()).contains(GRAMMAR_TOO_LARGE)) throw e;
                    strict = false; // 엄격 모드만 못 쓰는 것: 같은 시도 번호로 엄격 모드 없이 다시
                    attempt--;
                }
            }
            throw new AiUnavailableException("Claude 결과를 읽지 못했습니다(2번 시도): " + last.getMessage());
        }

        private <T> Result<T> one(ChatModel chat, String model, String system, String cachedPrefix, String user, BeanOutputConverter<T> converter,
                boolean strict, int maxTokens, long[] sum) {
            AnthropicChatOptions.Builder options = AnthropicChatOptions.builder().model(model).maxTokens(maxTokens);
            String sys = system;
            if (strict) options.outputSchema(converter.getJsonSchema());
            else sys = (has(system) ? system + "\n\n" : "") + converter.getFormat(); // 스키마를 안내로만 준다
            if (has(cachedPrefix)) options.cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_ONLY).build());
            Prompt prompt = new Prompt(messages(sys, cachedPrefix, user), options.build());
            ChatResponse res = SpringAi.call("Claude " + model, () -> chat.call(prompt));
            long[] u = usageOf(res);
            for (int i = 0; i < 3; i++) sum[i] += u[i];
            String finish = SpringAi.finishReason(res);
            if (finish.contains("refusal")) throw new AiUnavailableException("Claude 가 요청을 거절했습니다(refusal).");
            String text = SpringAi.text(res);
            if (text.isBlank()) throw new AiUnavailableException("Claude 응답이 비어 있습니다 (finish=" + finish + "). 출력 한도를 늘리세요.");
            T value;
            try {
                value = converter.convert(text);
            } catch (RuntimeException e) {
                throw new ParseFailure(e.getMessage());
            }
            if (value == null) throw new ParseFailure("결과가 비어 있습니다.");
            return new Result<>(value, sum[0], sum[1], sum[2]);
        }

        @Override
        public TextResult text(String model, String system, String user, int maxTokens, String effort) {
            ChatModel chat = chatModel(effort);
            Prompt prompt = new Prompt(messages(system, null, user), AnthropicChatOptions.builder().model(model).maxTokens(maxTokens).build());
            ChatResponse res = SpringAi.call("Claude " + model, () -> chat.call(prompt));
            String text = SpringAi.text(res);
            if (text.isBlank()) throw new AiUnavailableException("Claude 응답이 비어 있습니다 (finish=" + SpringAi.finishReason(res) + ").");
            long[] u = usageOf(res);
            return new TextResult(text, u[0], u[1], u[2]);
        }
    }

    /** 시스템 글(= system + 바뀌지 않는 앞부분)과 사용자 글. system 이 없으면 사용자 글만 */
    private static List<Message> messages(String system, String cachedPrefix, String user) {
        List<Message> out = new ArrayList<>();
        String sys = has(cachedPrefix) ? (has(system) ? system + "\n\n" + cachedPrefix : cachedPrefix) : system;
        if (has(sys)) out.add(new SystemMessage(sys));
        out.add(new UserMessage(user));
        return out;
    }
}
