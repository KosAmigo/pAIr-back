package com.pair.domain.taskgen.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenCheckResultDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("stages 가 있으면 JSON 으로 쓰고 읽어도 같다")
    void roundTrip() throws Exception {
        var r = new GenCheckResultDto("검사", false, "설명", GenCheckResultDto.WARN).withStages("WRITING", "CODE");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"stages\":[\"WRITING\",\"CODE\"]");
        assertThat(mapper.readValue(json, GenCheckResultDto.class)).isEqualTo(r);
    }

    @Test
    @DisplayName("stages 가 비어 있으면 JSON 에 칸이 없고, 칸이 없는 JSON(예전 report.json)은 빈 목록으로 읽힌다")
    void emptyOmittedAndMissingRead() throws Exception {
        String json = mapper.writeValueAsString(new GenCheckResultDto("검사", true, "통과"));
        assertThat(json).doesNotContain("stages");
        var old = mapper.readValue("{\"name\":\"검사\",\"ok\":true,\"detail\":\"통과\",\"level\":\"fail\"}", GenCheckResultDto.class);
        assertThat(old.stages()).isEmpty();
        assertThat(mapper.readValue("{\"name\":\"검사\",\"ok\":true,\"detail\":\"x\",\"level\":\"warn\",\"stages\":null}", GenCheckResultDto.class).stages()).isEqualTo(List.of());
    }
}
