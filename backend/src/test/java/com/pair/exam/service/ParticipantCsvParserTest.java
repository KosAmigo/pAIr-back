package com.pair.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParticipantCsvParserTest {

    @Test
    @DisplayName("영문 헤더, 따옴표 안 쉼표·따옴표·줄바꿈, 빈 줄을 읽고 CSV 줄 번호를 붙인다")
    void quotedCells() {
        String csv = "email,name\n\"a@test.dev\",\"김 \"\"별명\"\"\"\n\n\"b@test.dev\",\"줄\n바꿈\"\n";

        var rows = ParticipantCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8), 10);

        assertThat(rows).extracting(r -> r.line() + ":" + r.email() + ":" + r.name())
                .containsExactly("2:a@test.dev:김 \"별명\"", "4:b@test.dev:줄\n바꿈");
    }

    @Test
    @DisplayName("UTF-8 이 아니면 MS949(엑셀 한글판 CSV)로 읽는다")
    void ms949() {
        byte[] csv = "이름,이메일\r\n홍길동,hong@test.dev\r\n".getBytes(Charset.forName("MS949"));

        assertThat(ParticipantCsvParser.parse(csv, 10)).singleElement()
                .satisfies(r -> assertThat(r.name()).isEqualTo("홍길동"));
    }

    @Test
    @DisplayName("양식이 아니거나 인원이 너무 많거나 따옴표가 안 닫히면 거절")
    void rejects() {
        assertThatThrownBy(() -> ParticipantCsvParser.parse("성명,메일\n".getBytes(StandardCharsets.UTF_8), 10))
                .hasMessageContaining("'이름', '이메일'");
        assertThatThrownBy(() -> ParticipantCsvParser.parse("이름,이메일\na,a@x.y\nb,b@x.y\n".getBytes(StandardCharsets.UTF_8), 1))
                .hasMessageContaining("1명까지");
        assertThatThrownBy(() -> ParticipantCsvParser.parse("이름,이메일\n\"a,a@x.y\n".getBytes(StandardCharsets.UTF_8), 10))
                .hasMessageContaining("따옴표");
        assertThatThrownBy(() -> ParticipantCsvParser.parse(new byte[0], 10)).hasMessageContaining("비어");
    }

    @Test
    @DisplayName("토큰 해시: SHA-256 hex 64자, 같은 토큰은 같은 해시")
    void tokenHash() {
        String t = InviteTokens.next();
        assertThat(InviteTokens.hash(t)).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(InviteTokens.hash(t));
        assertThat(InviteTokens.hash(t)).isNotEqualTo(InviteTokens.hash(InviteTokens.next()));
    }
}
