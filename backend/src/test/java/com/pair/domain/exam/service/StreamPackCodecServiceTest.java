package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.exam.dto.StreamFrameDto;

class StreamPackCodecServiceTest {

    @Test
    @DisplayName("묶음을 만들고 다시 풀면 사진과 받은 시각이 그대로 나온다")
    void roundTrip() {
        long start = 1_764_000_000_000L;
        var frames = List.of(new StreamFrameDto(start + 120, new byte[] {1, 2, 3}), new StreamFrameDto(start + 1_050, new byte[] {9}));

        byte[] pack = StreamPackCodecService.pack(1, start, frames);
        var out = StreamPackCodecService.unpack(pack);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).atMs()).isEqualTo(start + 120);
        assertThat(out.get(0).jpeg()).containsExactly(1, 2, 3);
        assertThat(out.get(1).atMs()).isEqualTo(start + 1_050);
        assertThat(new String(pack, 0, 6)).isEqualTo("PAIRPK"); // 계약의 magic
    }

    @Test
    @DisplayName("magic 이 다르거나 잘린 파일은 읽지 않는다")
    void rejectsBroken() {
        assertThatThrownBy(() -> StreamPackCodecService.unpack(new byte[40])).isInstanceOf(IllegalArgumentException.class);
        byte[] pack = StreamPackCodecService.pack(2, 0, List.of(new StreamFrameDto(5, new byte[] {1, 2, 3, 4})));
        byte[] cut = java.util.Arrays.copyOf(pack, pack.length - 2);
        assertThatThrownBy(() -> StreamPackCodecService.unpack(cut)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사진이 없거나 10장을 넘는 묶음은 만들지 않는다")
    void rejectsBadCount() {
        assertThatThrownBy(() -> StreamPackCodecService.pack(1, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
