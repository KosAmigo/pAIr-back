package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatestFrameMailboxServiceTest {

    @Test
    @DisplayName("보내는 중에 쌓인 프레임은 가장 최신 한 장만 남기고 나머지는 버린다")
    void keepsOnlyLatest() {
        var box = new LatestFrameMailboxService();
        assertThat(box.offer(new byte[] {1})).isTrue(); // 이 호출이 전송을 시작한다
        assertThat(box.offer(new byte[] {2})).isFalse();
        assertThat(box.offer(new byte[] {3})).isFalse();

        assertThat(box.take()).containsExactly(3);
        assertThat(box.dropped()).isEqualTo(2); // 1 은 2 가, 2 는 3 이 덮어써서 버려졌다
        assertThat(box.take()).isNull(); // 비면 전송 상태가 끝난다
        assertThat(box.offer(new byte[] {4})).isTrue(); // 다시 시작할 수 있다
    }

    @Test
    @DisplayName("프레임이 없으면 take 는 null")
    void emptyTake() {
        assertThat(new LatestFrameMailboxService().take()).isNull();
    }
}
