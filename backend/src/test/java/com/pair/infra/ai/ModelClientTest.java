package com.pair.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pair.global.config.AiKeyProperties;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 모델 호출 단일 입구: 모델 이름으로 회사 고르기, 추론 강도·출력 한도 전달, 토큰, 키 없음 오류를 가짜 구현으로 확인한다(실제 호출 없음) */
class ModelClientTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, "medium", 48000, null, null, null, null, null, null, null, null, null, null, null, "high");

    /** 불린 쪽과 받은 값을 기록하고 정해진 토큰을 돌려주는 가짜 */
    static class Spy implements ModelClient.Backend {
        final String name;
        final List<String> calls;

        Spy(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public <T> ModelClient.Result<T> json(String model, String system, String cachedPrefix, String user, Class<T> type, int maxTokens, String effort) {
            calls.add("json " + name + ":" + model + ":" + maxTokens + ":" + effort + ":" + cachedPrefix);
            return new ModelClient.Result<>(null, 7, 9, 3);
        }

        @Override
        public ModelClient.TextResult text(String model, String system, String user, int maxTokens, String effort) {
            calls.add("text " + name + ":" + model + ":" + maxTokens + ":" + effort + ":" + system);
            return new ModelClient.TextResult("답", 11, 13, 0);
        }
    }

    private final List<String> calls = new ArrayList<>();
    private final ModelClient client = new ModelClient(PROPS, new Spy("openai", calls), new Spy("anthropic", calls));

    @Test
    @DisplayName("모델 이름으로 회사를 고른다: claude-* 는 Anthropic, gpt-* 는 OpenAI(대소문자 무시), 그 밖이나 빈 이름은 분명한 오류")
    void routesByModelName() {
        client.json("claude-sonnet-5-5", "s", "u", String.class, 1000, "low");
        client.json("gpt-6.1-terra", "s", "u", String.class, 1000, "low");
        client.json("GPT-6-luna", "s", "u", String.class, 1000, "low");
        client.text("Claude-Haiku-5-5", "s", "u", 1000, "low");
        client.text("gpt-6-luna", "s", "u", 1000, "low");

        assertThat(calls).extracting(c -> c.substring(0, c.indexOf(':'))).containsExactly("json anthropic", "json openai", "json openai", "text anthropic", "text openai");
        for (String bad : new String[] {"o3-mini", "gemini-2", "", null}) {
            assertThatThrownBy(() -> client.json(bad, "s", "u", String.class, 1000, "low")).isInstanceOf(AiUnavailableException.class).hasMessageContaining("모르는 모델 이름");
            assertThatThrownBy(() -> client.text(bad, "s", "u", 1000, "low")).isInstanceOf(AiUnavailableException.class);
        }
        assertThat(calls).hasSize(5); // 오류일 때는 어느 회사도 부르지 않는다
    }

    @Test
    @DisplayName("출력 한도·추론 강도·캐시 앞부분이 그대로 넘어가고, 도메인 인터페이스(generate, solve)는 설정 기본값(max-tokens, effort, solver-effort)을 쓴다")
    void passesMaxTokensAndEffortThrough() {
        client.json("claude-sonnet-5-5", "s", "앞부분", "u", String.class, 24000, "medium");
        client.json("gpt-6.1-terra", "s", "u", String.class, 16000, "high");
        client.generate("gpt-6.1-terra", "s", "u", String.class, 2000, "low");
        client.generate("claude-sonnet-5-5", "s", "u", String.class); // 모델·강도를 따로 안 정한 호출: pair.taskgen.max-tokens 와 effort
        String answer = client.solve("gpt-6.1-terra", "문제"); // 풀이 검사: solver-effort, 시스템 글 없음

        assertThat(calls).containsExactly("json anthropic:claude-sonnet-5-5:24000:medium:앞부분", "json openai:gpt-6.1-terra:16000:high:null",
                "json openai:gpt-6.1-terra:2000:low:null", "json anthropic:claude-sonnet-5-5:48000:medium:null", "text openai:gpt-6.1-terra:16000:high:null");
        assertThat(answer).isEqualTo("답");
    }

    @Test
    @DisplayName("토큰: 도메인 인터페이스는 입력·출력 토큰을 그대로 돌려주고, 입구는 캐시에서 읽은 입력 토큰도 함께 돌려준다")
    void tokensArePassedBack() {
        var out = client.generate("gpt-6.1-terra", "s", "u", String.class, 1000, "low");
        assertThat(out.inputTokens()).isEqualTo(7);
        assertThat(out.outputTokens()).isEqualTo(9);
        var r = client.json("claude-sonnet-5-5", "s", "u", String.class, 1000, "low");
        assertThat(r.cachedInputTokens()).isEqualTo(3);
        assertThat(client.text("gpt-6.1-terra", null, "u", 1000, "low").inputTokens()).isEqualTo(11);
    }

    @Test
    @DisplayName("토큰 세기: 네이티브 사용량이 없으면 Spring AI 공통 값(프롬프트·완성 토큰), 비어 있으면 0")
    void usageFallback() {
        var res = new ChatResponse(List.of(new Generation(new AssistantMessage("x"))), ChatResponseMetadata.builder().usage(new DefaultUsage(120, 45, 165)).build());
        assertThat(ModelClient.usageOf(res)).containsExactly(120, 45, 0);
        var empty = new ChatResponse(List.of(new Generation(new AssistantMessage("x"))), ChatResponseMetadata.builder().build());
        assertThat(ModelClient.usageOf(empty)).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("키가 없으면 AiUnavailableException: Claude 모델은 ANTHROPIC 키, OpenAI 모델은 OPENAI 키를 안내한다")
    void missingKeyThrowsUnavailable() {
        var real = new ModelClient(PROPS, new AiKeyProperties("", ""));
        assertThatThrownBy(() -> real.json("claude-sonnet-5-5", "s", "u", String.class, 1000, "medium")).isInstanceOf(AiUnavailableException.class).hasMessageContaining("Anthropic API 키");
        assertThatThrownBy(() -> real.text("claude-sonnet-5-5", null, "u", 1000, "medium")).isInstanceOf(AiUnavailableException.class).hasMessageContaining("Anthropic API 키");
        assertThatThrownBy(() -> real.json("gpt-6.1-terra", "s", "u", String.class, 1000, "medium")).isInstanceOf(AiUnavailableException.class).hasMessageContaining("OpenAI API 키");
        assertThatThrownBy(() -> real.solve("gpt-6.1-terra", "p")).isInstanceOf(AiUnavailableException.class).hasMessageContaining("OpenAI API 키");
        // 한쪽 키만 있어도 다른 쪽 모델은 같은 오류
        var onlyOpenai = new ModelClient(PROPS, new AiKeyProperties("sk-test", ""));
        assertThatThrownBy(() -> onlyOpenai.json("claude-sonnet-5-5", "s", "u", String.class, 1000, "medium")).hasMessageContaining("Anthropic API 키");
    }

    // ── Anthropic 엄격 모드(outputSchema) 처리 ─────────────────────────────

    record Small(String name) {}

    /** 요청을 기록하고 정해진 글/예외를 차례로 돌려주는 가짜 ChatModel */
    static class FakeChat implements org.springframework.ai.chat.model.ChatModel {
        final List<org.springframework.ai.chat.prompt.Prompt> prompts = new ArrayList<>();
        final java.util.Deque<Object> script = new java.util.ArrayDeque<>();

        FakeChat then(Object textOrError) {
            script.add(textOrError);
            return this;
        }

        @Override
        public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            prompts.add(prompt);
            Object next = script.poll();
            if (next instanceof RuntimeException e) throw e;
            return new ChatResponse(List.of(new Generation(new AssistantMessage((String) next))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
        }

        boolean strict(int i) {
            return ((org.springframework.ai.anthropic.AnthropicChatOptions) prompts.get(i).getOptions()).getOutputSchema() != null;
        }

        String system(int i) {
            return prompts.get(i).getSystemMessage().getText();
        }
    }

    private static ModelClient.Backend anthropic(FakeChat chat) {
        return new ModelClient.AnthropicBackend(new AiKeyProperties("", "sk-test"), e -> chat);
    }

    @Test
    @DisplayName("스키마가 작으면 엄격 모드(outputSchema)로 부른다")
    void smallSchemaIsStrict() {
        var chat = new FakeChat().then("{\"name\":\"x\"}");
        var r = anthropic(chat).json("claude-sonnet-5-5", "s", null, "u", Small.class, 1000, "medium");

        assertThat(r.value()).isEqualTo(new Small("x"));
        assertThat(chat.strict(0)).isTrue();
    }

    @Test
    @DisplayName("큰 스키마(설계도)는 엄격 모드 없이 부른다: outputSchema 를 보내지 않고 스키마를 시스템 글로 안내하며, 코드 펜스가 붙은 응답도 코드에서 읽는다")
    void bigSchemaIsNotStrict() {
        var chat = new FakeChat().then("```json\n{\"title\":\"설계도\"}\n```");
        var r = anthropic(chat).json("claude-sonnet-5-5", "시스템", null, "u", com.pair.domain.taskgen.dto.BlueprintDto.class, 1000, "medium");

        assertThat(r.value().title()).isEqualTo("설계도");
        assertThat(chat.strict(0)).isFalse();
        assertThat(chat.system(0)).contains("시스템").contains("scenario");
    }

    @Test
    @DisplayName("엄격 모드가 '문법이 너무 크다'(400)로 거절되면 엄격 모드 없이 바로 다시 부르고, 파싱 재시도 횟수는 쓰지 않는다")
    void fallsBackWhenGrammarTooLarge() {
        var chat = new FakeChat().then(new AiUnavailableException("Claude claude-sonnet-5-5 호출 실패: 400 The compiled grammar is too large"))
                .then("{\"name\":\"ok\"}");
        var r = anthropic(chat).json("claude-sonnet-5-5", "s", null, "u", Small.class, 1000, "medium");

        assertThat(r.value()).isEqualTo(new Small("ok"));
        assertThat(chat.prompts).hasSize(2);
        assertThat(chat.strict(0)).isTrue();
        assertThat(chat.strict(1)).isFalse();
    }

    @Test
    @DisplayName("결과를 읽지 못하면 1번 다시 부르고(토큰은 합산), 두 번 다 못 읽으면 AiUnavailableException")
    void retriesOnceOnParseFailure() {
        var chat = new FakeChat().then("이건 JSON 이 아닙니다").then("{\"name\":\"ok\"}");
        var r = anthropic(chat).json("claude-sonnet-5-5", "s", null, "u", Small.class, 1000, "medium");

        assertThat(r.value()).isEqualTo(new Small("ok"));
        assertThat(chat.prompts).hasSize(2);
        assertThat(r.inputTokens()).isEqualTo(20); // 두 호출의 토큰 합
        assertThat(r.outputTokens()).isEqualTo(10);

        var bad = new FakeChat().then("깨짐1").then("깨짐2").then("{\"name\":\"안 씀\"}");
        assertThatThrownBy(() -> anthropic(bad).json("claude-sonnet-5-5", "s", null, "u", Small.class, 1000, "medium"))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("읽지 못했습니다");
        assertThat(bad.prompts).hasSize(2);
    }

    @Test
    @DisplayName("다른 오류(예: 401)는 다시 부르지 않고 그대로 던진다")
    void otherErrorsAreNotRetried() {
        var chat = new FakeChat().then(new AiUnavailableException("Claude API 키가 올바르지 않습니다 (401)."));
        assertThatThrownBy(() -> anthropic(chat).json("claude-sonnet-5-5", "s", null, "u", Small.class, 1000, "medium")).hasMessageContaining("401");
        assertThat(chat.prompts).hasSize(1);
    }

    @Test
    @DisplayName("과제 생성 단계 타입의 스키마 크기를 확인한다(기준 초과면 엄격 모드를 쓰지 않는 타입)")
    void stageSchemaSizes() {
        for (Class<?> t : List.of(com.pair.domain.taskgen.dto.BlueprintDto.class, com.pair.domain.taskgen.dto.DevWritingDto.class,
                com.pair.domain.taskgen.dto.DevCodeDto.class, com.pair.domain.taskgen.dto.DevAnswerDto.class, com.pair.domain.taskgen.dto.BizWritingDto.class,
                com.pair.domain.taskgen.dto.BizQuestionsDto.class, com.pair.domain.taskgen.dto.BizAnswerDto.class, com.pair.domain.taskgen.dto.ReviewResultDto.class)) {
            int n = new org.springframework.ai.converter.BeanOutputConverter<>(t).getJsonSchema().length();
            System.out.println("[schema-size] " + t.getSimpleName() + " " + n + (n > ModelClient.AnthropicBackend.STRICT_MAX_SCHEMA_CHARS ? " (비엄격)" : ""));
        }
    }

    @Test
    @DisplayName("가짜 AI: 모델을 부르지 않고 기다린 뒤 빈 결과를 준다(목록은 null 이 아니라 빈 목록)")
    void fakeBackendReturnsEmptyResults() {
        var fake = new ModelClient(PROPS, new ModelClient.FakeBackend(java.time.Duration.ofMillis(50), 0),
                new ModelClient.FakeBackend(java.time.Duration.ofMillis(50), 0));
        long start = System.nanoTime();

        var r = fake.json("claude-haiku-5-5", "s", "u", com.pair.domain.scoring.service.ScoringService.JudgeResult.class, 1000, "low");

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(45));
        assertThat(r.value().verdicts()).isEmpty();
        assertThat(r.inputTokens()).isZero();
        assertThat(fake.text("gpt-6-luna", null, "u", 100, null).text()).isNotBlank();
    }
}
