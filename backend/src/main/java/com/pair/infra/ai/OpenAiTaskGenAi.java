package com.pair.infra.ai;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import com.pair.global.config.AiKeys;
import com.pair.domain.taskgen.service.TaskGenAi;
import com.pair.global.config.TaskGenProperties;

/** 과제 생성용 OpenAI 호출. OpenAiJudge 와 같은 방식(JSON Schema 구조화 출력)으로 부른다 */
@Component
public class OpenAiTaskGenAi implements TaskGenAi {

    private final TaskGenProperties cfg;
    private final AiKeys keys;
    private volatile OpenAiChatModel chatModel;

    public OpenAiTaskGenAi(TaskGenProperties cfg, AiKeys keys) {
        this.cfg = cfg;
        this.keys = keys;
    }

    private OpenAiChatModel chatModel() {
        if (chatModel == null) {
            synchronized (this) {
                if (chatModel == null) {
                    if (!keys.hasOpenai()) {
                        throw new AiUnavailableException("OpenAI API 키(spring.ai.openai.api-key, 환경 변수 OPENAI_API_KEY)가 없어 과제를 생성할 수 없습니다.");
                    }
                    chatModel = SpringAi.openAi(keys.openai());
                }
            }
        }
        return chatModel;
    }

    @Override
    public <T> Output<T> generate(String model, String system, String user, Class<T> type) {
        return generate(model, system, user, type, cfg.maxTokens(), cfg.effort());
    }

    @Override
    public <T> Output<T> generate(String model, String system, String user, Class<T> type, int maxTokens, String effort) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .maxCompletionTokens(maxTokens)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema()));
        if (!effort.equalsIgnoreCase("none")) options.reasoningEffort(effort);
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options.build());
        ChatResponse res = SpringAi.call("OpenAI 과제 생성", () -> chatModel().call(prompt));
        String text = SpringAi.text(res);
        if (text.isBlank()) {
            throw new AiUnavailableException("OpenAI 과제 생성 응답이 비어 있습니다 (finish=" + SpringAi.finishReason(res) + "). 출력 한도를 늘리세요.");
        }
        T value;
        try {
            value = converter.convert(text);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("OpenAI 과제 생성 결과를 읽지 못했습니다: " + e.getMessage());
        }
        if (value == null) throw new AiUnavailableException("OpenAI 과제 생성 결과가 비어 있습니다.");
        OpenAiApi.Usage u = res.getMetadata().getUsage().getNativeUsage() instanceof OpenAiApi.Usage x ? x : null;
        long in = u != null ? SpringAi.n(u.promptTokens()) : SpringAi.n(res.getMetadata().getUsage().getPromptTokens());
        long out = u != null ? SpringAi.n(u.completionTokens()) : SpringAi.n(res.getMetadata().getUsage().getCompletionTokens());
        return new Output<>(value, in, out);
    }
}
