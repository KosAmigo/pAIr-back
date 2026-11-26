package com.pair.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

/** 과제 생성 배치 요청 본문이 바로 호출(ModelClient)과 같은 스키마·옵션인지 확인한다 */
class BatchClientGenerateReqTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final BatchClient client = new BatchClient(null, null, JSON);

    @Test
    @DisplayName("OpenAI 본문: 같은 JSON 스키마(strict), 출력 한도, 추론 강도, system·user 글")
    void openAiBody() throws Exception {
        JsonNode b = client.generateReq("c1", "gpt-6.1-sol", "SYS", "USER", ReviewResultDto.class, 16000, "medium").body();
        assertThat(b.path("model").asText()).isEqualTo("gpt-6.1-sol");
        assertThat(b.path("max_completion_tokens").asInt()).isEqualTo(16000);
        assertThat(b.path("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(b.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(b.path("messages").path(0).path("content").asText()).isEqualTo("SYS");
        assertThat(b.path("messages").path(1).path("content").asText()).isEqualTo("USER");
        JsonNode js = b.path("response_format").path("json_schema");
        assertThat(b.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(js.path("strict").asBoolean()).isTrue();
        assertThat(js.path("schema")).isEqualTo(JSON.readTree(new BeanOutputConverter<>(ReviewResultDto.class).getJsonSchema()));
    }

    @Test
    @DisplayName("OpenAI 본문: 추론 강도를 정하지 않으면 보내지 않는다")
    void openAiWithoutEffort() {
        JsonNode b = client.generateReq("c1", "gpt-6-luna", null, "USER", ReviewResultDto.class, 2000, " ").body();
        assertThat(b.has("reasoning_effort")).isFalse();
        assertThat(b.path("messages")).hasSize(1);
    }

    @Test
    @DisplayName("Anthropic 본문(작은 스키마): 엄격 모드 스키마 output_config.format, effort 소문자, max_tokens")
    void anthropicStrictBody() throws Exception {
        String schema = new BeanOutputConverter<>(ReviewResultDto.class).getJsonSchema();
        assumeTrue(schema.length() <= ModelClient.AnthropicBackend.STRICT_MAX_SCHEMA_CHARS);
        JsonNode b = client.generateReq("c2", "claude-sonnet-5-5", "SYS", "USER", ReviewResultDto.class, 16000, "Medium").body();
        assertThat(b.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(b.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(b.path("system").asText()).isEqualTo("SYS");
        assertThat(b.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(b.path("messages").path(0).path("content").asText()).isEqualTo("USER");
        assertThat(b.path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(b.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(b.path("output_config").path("format").path("schema")).isEqualTo(JSON.readTree(schema));
    }

    @Test
    @DisplayName("Anthropic 본문(큰 스키마): 엄격 모드 없이 스키마를 system 안내 글로만 준다(ModelClient 와 같은 기준)")
    void anthropicLargeSchemaBody() {
        String schema = new BeanOutputConverter<>(BlueprintDto.class).getJsonSchema();
        assumeTrue(schema.length() > ModelClient.AnthropicBackend.STRICT_MAX_SCHEMA_CHARS);
        JsonNode b = client.generateReq("c3", "claude-sonnet-5-5", "SYS", "USER", BlueprintDto.class, 24000, "none").body();
        assertThat(b.has("output_config")).isFalse();
        assertThat(b.path("system").asText()).startsWith("SYS\n\n").contains(new BeanOutputConverter<>(BlueprintDto.class).getFormat());
    }

    @Test
    @DisplayName("모델 이름으로 회사를 고른다: claude-* 는 Anthropic, gpt-* 는 OpenAI, 그 밖은 오류")
    void routesByModelName() {
        assertThat(client.generateReq("a", "claude-haiku-5-5", null, "u", ReviewResultDto.class, 1000, "low").body().has("max_tokens")).isTrue();
        assertThat(client.generateReq("a", "gpt-6.1-terra", null, "u", ReviewResultDto.class, 1000, "low").body().has("max_completion_tokens")).isTrue();
        assertThatThrownBy(() -> client.generateReq("a", "llama-3", null, "u", ReviewResultDto.class, 1000, "low")).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("배치로 받은 글을 바로 호출과 같은 변환으로 읽는다")
    void readsAnswer() {
        ReviewResultDto r = BatchClient.read(ReviewResultDto.class, "{\"violations\":[{\"item\":\"M2\",\"quote\":\"q\",\"location\":\"l\",\"fix\":\"f\"}]}");
        assertThat(r.violations()).hasSize(1);
        assertThatThrownBy(() -> BatchClient.read(ReviewResultDto.class, "not json")).isInstanceOf(AiUnavailableException.class);
    }
}
