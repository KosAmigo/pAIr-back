package com.pair.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배치 결과 파일의 한글이 깨지지 않는지 확인한다(2026-11-28 g22: ISO-8859-1 로 읽혀 검수 인용 문장이 깨짐) */
class BatchClientResultCharsetTest {

    @Test
    @DisplayName("배치 결과 바이트를 UTF-8 로 읽어 한글이 그대로 남는다")
    void readsKoreanAsUtf8() {
        String line = "{\"custom_id\":\"a\",\"text\":\"소수 첫째 자리까지 답하십시오.\"}";
        byte[] body = line.getBytes(StandardCharsets.UTF_8);

        assertThat(BatchClient.utf8(body)).isEqualTo(line);
        // 예전 방식(charset 없는 응답을 ISO-8859-1 로 읽음)은 깨진다
        assertThat(new String(body, StandardCharsets.ISO_8859_1)).isNotEqualTo(line);
    }

    @Test
    @DisplayName("본문이 없으면 빈 문자열")
    void nullBodyIsEmpty() {
        assertThat(BatchClient.utf8(null)).isEmpty();
    }
}
