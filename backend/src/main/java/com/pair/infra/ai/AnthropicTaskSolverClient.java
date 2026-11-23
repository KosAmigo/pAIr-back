package com.pair.infra.ai;

import java.util.List;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.pair.global.config.AiKeyProperties;
import com.pair.domain.taskgen.service.TaskGenSolver;

/** 비개발 과제 풀이 검사용 Claude 호출 (생성 모델과 다른 회사 모델) */
@Component
public class AnthropicTaskSolverClient implements TaskGenSolver {

    private final AiKeyProperties keys;
    private volatile AnthropicChatModel chatModel;

    public AnthropicTaskSolverClient(AiKeyProperties keys) {
        this.keys = keys;
    }

    private AnthropicChatModel chatModel() {
        if (chatModel == null) {
            synchronized (this) {
                if (chatModel == null) {
                    if (!keys.hasAnthropic()) {
                        throw new AiUnavailableException("Anthropic API 키(spring.ai.anthropic.api-key, 환경 변수 ANTHROPIC_API_KEY)가 없어 풀이 검사를 할 수 없습니다.");
                    }
                    chatModel = SpringAi.anthropic(keys.anthropic(), "medium");
                }
            }
        }
        return chatModel;
    }

    @Override
    public String solve(String model, String prompt) {
        AnthropicChatOptions options = AnthropicChatOptions.builder().model(model).maxTokens(16000).build();
        Prompt p = new Prompt(List.of(new UserMessage(prompt)), options);
        ChatResponse res = SpringAi.call("Claude 풀이", () -> chatModel().call(p));
        String text = SpringAi.text(res);
        if (text.isBlank()) throw new AiUnavailableException("Claude 풀이 응답이 비어 있습니다 (finish=" + SpringAi.finishReason(res) + ").");
        return text;
    }
}
