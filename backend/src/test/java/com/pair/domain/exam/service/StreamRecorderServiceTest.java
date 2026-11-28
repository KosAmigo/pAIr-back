package com.pair.domain.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pair.domain.exam.MutableClock;
import com.pair.domain.exam.entity.ProctorStreamPack;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.repository.ProctorStreamPackRepository;
import com.pair.global.config.VisionProperties;
import com.pair.infra.storage.SnapshotStorage;

class StreamRecorderServiceTest {

    /** 메모리 파일 저장소 */
    static class MemoryStorage implements SnapshotStorage {
        final Map<String, byte[]> files = new HashMap<>();
        @Override public void put(String key, byte[] bytes) { files.put(key, bytes); }
        @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(files.get(key)); }
        @Override public void delete(String key) { files.remove(key); }
    }

    static final long T0 = 1_764_000_000_000L - 1_764_000_000_000L % 10_000; // 10초 경계
    final MemoryStorage storage = new MemoryStorage();
    final ProctorStreamPackRepository packs = mock(ProctorStreamPackRepository.class);
    final List<ProctorStreamPack> saved = new ArrayList<>();
    final MutableClock clock = new MutableClock(Instant.ofEpochMilli(T0));
    final StreamRecorderService recorder = new StreamRecorderService(packs, mock(ExamSessionRepository.class), storage, VisionProperties.defaults(), clock);

    StreamRecorderServiceTest() {
        when(packs.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(packs.findByParticipantIdAndChannelAndStartAt(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("같은 초에 온 사진은 첫 장만 쓰고, 이벤트를 뽑기 전에 flush 하면 지금까지 모인 것이 올라간다")
    void onePerSecondAndFlush() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 100, jpeg);
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 600, jpeg); // 같은 초: 버린다
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 1_200, jpeg);
        recorder.flush(7L);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getFrameCount()).isEqualTo(2);
        assertThat(saved.get(0).getStartAt()).isEqualTo(Instant.ofEpochMilli(T0)); // 10초 정렬 구간
        String key = SnapshotStorage.streamKeyOf(1, 7, "webcam", T0);
        assertThat(storage.files).containsKey(key);
        assertThat(StreamPackCodecService.unpack(storage.files.get(key))).hasSize(2);
    }

    @Test
    @DisplayName("구간이 바뀌면 이전 구간 묶음이 올라가고, 채널은 따로 모은다")
    void rollsOverWindow() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 100, jpeg);
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.PHONE, T0 + 100, jpeg);
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 10_100, jpeg); // 다음 10초 구간
        Thread.sleep(300); // 올리기는 별도 스레드에서 한다
        recorder.flush(7L);

        assertThat(storage.files.keySet()).contains(
                SnapshotStorage.streamKeyOf(1, 7, "webcam", T0),
                SnapshotStorage.streamKeyOf(1, 7, "webcam", T0 + 10_000),
                SnapshotStorage.streamKeyOf(1, 7, "phone", T0));
    }

    @Test
    @DisplayName("같은 구간을 다시 flush 해도 새 사진이 없으면 다시 올리지 않는다")
    void noReuploadWithoutNewFrames() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 100, jpeg);
        recorder.flush(7L);
        recorder.flush(7L);
        assertThat(saved).hasSize(1);
        recorder.offer(1L, 7L, ProctorStreamPack.Channel.WEBCAM, T0 + 2_100, jpeg);
        recorder.flush(7L);
        assertThat(saved).hasSize(2); // 같은 키를 덮어쓰며 2장이 된다
        assertThat(StreamPackCodecService.unpack(storage.files.get(SnapshotStorage.streamKeyOf(1, 7, "webcam", T0)))).hasSize(2);
    }
}
