package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.dto.StreamFrameDto;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorStreamPack;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.repository.ProctorStreamPackRepository;
import com.pair.global.config.VisionProperties;
import com.pair.infra.storage.SnapshotStorage;

import jakarta.annotation.PreDestroy;

/**
 * 사진 상시 저장 (서버 추론 회차, 시험 중 `exam` 모드). 채널(웹캠·휴대폰)마다 서버 시계 기준 1초에 처음 온 한 장을 골라,
 * 10초 정렬 구간(startMs = floor(recvMs/10000)*10000)마다 한 묶음으로 파일 저장소에 올리고 DB 에는 묶음마다 한 행을 둔다.
 * 같은 구간을 다시 올려도 같은 키로 덮어쓴다(이벤트 구간을 뽑으려고 중간에 한 번 올린 뒤 나머지를 더해 다시 올리는 경우).
 * 올리기는 1·2·4초 간격으로 3번 다시 시도하고, 그래도 안 되면 그 묶음을 버린다. 백엔드가 비정상 종료하면 최대 10초분을 잃는다.
 * 사진·키는 로그에 남기지 않는다. 보관은 회차 종료 뒤 stream-retention-days(3일), 지우는 일은 {@link #purgeExpired()}.
 */
@Service
public class StreamRecorderService {
    private static final Logger log = LoggerFactory.getLogger(StreamRecorderService.class);
    private static final int PURGE_BATCH = 500;
    private static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 4_000};
    static final long WINDOW_MS = 10_000;

    /** 채널 하나의 모으는 중인 구간. 상태는 slot 을 잠그고 바꾼다 */
    private static final class Slot {
        final Long sessionId;
        final Long participantId;
        final ProctorStreamPack.Channel channel;
        long lastSecond = Long.MIN_VALUE;
        long windowStart = Long.MIN_VALUE;
        List<StreamFrameDto> frames = new ArrayList<>();
        /** 이 구간에서 이미 올린 사진 수 (더 모이지 않았으면 다시 올리지 않는다) */
        int uploaded;

        Slot(Long sessionId, Long participantId, ProctorStreamPack.Channel channel) {
            this.sessionId = sessionId;
            this.participantId = participantId;
            this.channel = channel;
        }
    }

    /** 올릴 묶음 한 개의 복사본 */
    private record Batch(Slot slot, long windowStart, List<StreamFrameDto> frames) {}

    private final ProctorStreamPackRepository packs;
    private final ExamSessionRepository sessions;
    private final SnapshotStorage storage;
    private final VisionProperties props;
    private final Clock clock;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final ExecutorService uploader = Executors.newVirtualThreadPerTaskExecutor();

    public StreamRecorderService(ProctorStreamPackRepository packs, ExamSessionRepository sessions, SnapshotStorage storage,
            VisionProperties props, Clock clock) {
        this.packs = packs;
        this.sessions = sessions;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    /** 사진 1장을 받았다. 같은 초에 이미 고른 장이 있으면 버린다 (채널마다 초당 1장) */
    public void offer(Long sessionId, Long participantId, ProctorStreamPack.Channel channel, long recvMs, byte[] jpeg) {
        Slot slot = slots.computeIfAbsent(key(participantId, channel), k -> new Slot(sessionId, participantId, channel));
        Batch finished = null;
        synchronized (slot) {
            long second = Math.floorDiv(recvMs, 1000L);
            if (second == slot.lastSecond) return;
            slot.lastSecond = second;
            long window = Math.floorDiv(recvMs, WINDOW_MS) * WINDOW_MS;
            if (window != slot.windowStart) {
                if (slot.frames.size() > slot.uploaded) finished = new Batch(slot, slot.windowStart, List.copyOf(slot.frames));
                slot.frames = new ArrayList<>();
                slot.uploaded = 0;
                slot.windowStart = window;
            }
            slot.frames.add(new StreamFrameDto(recvMs, jpeg));
        }
        if (finished != null) {
            Batch toSave = finished;
            runUpload(() -> persist(toSave));
        }
    }

    /** 이 응시자의 모으는 중인 사진을 지금 올린다 (이벤트 구간을 뽑기 직전에 부른다). 올리기가 끝난 뒤 돌아온다 */
    public void flush(Long participantId) {
        for (ProctorStreamPack.Channel ch : ProctorStreamPack.Channel.values()) {
            Slot slot = slots.get(key(participantId, ch));
            if (slot != null) flushSlot(slot, false);
        }
    }

    /** 연결·시험이 끝난 응시자의 남은 사진을 올리고 모으던 칸을 치운다 */
    public void close(Long participantId) {
        for (ProctorStreamPack.Channel ch : ProctorStreamPack.Channel.values()) {
            Slot slot = slots.remove(key(participantId, ch));
            if (slot != null) runUpload(() -> flushSlot(slot, true));
        }
    }

    /** 사진이 더 안 들어와도 구간이 끝난 칸은 올리고 비운다 (구간이 끝난 뒤 1초 안) */
    @Scheduled(fixedDelay = 1_000)
    public void flushIdle() {
        long now = clock.millis();
        for (Slot slot : slots.values()) {
            boolean due;
            synchronized (slot) {
                due = !slot.frames.isEmpty() && now >= slot.windowStart + WINDOW_MS;
            }
            if (due) runUpload(() -> flushSlot(slot, true));
        }
    }

    /** @param finish true 면 올린 뒤 구간을 비운다. false 면 구간을 그대로 두고(이어서 더 모일 수 있음) 지금까지 모인 것만 올린다 */
    private void flushSlot(Slot slot, boolean finish) {
        Batch batch = null;
        synchronized (slot) {
            if (slot.frames.size() > slot.uploaded) batch = new Batch(slot, slot.windowStart, List.copyOf(slot.frames));
            if (finish) {
                slot.frames = new ArrayList<>();
                slot.uploaded = 0;
                slot.windowStart = Long.MIN_VALUE;
            } else if (batch != null) {
                slot.uploaded = batch.frames().size();
            }
        }
        if (batch != null) persist(batch);
    }

    private void persist(Batch batch) {
        Slot slot = batch.slot();
        String key = SnapshotStorage.streamKeyOf(slot.sessionId, slot.participantId, slot.channel.wire(), batch.windowStart());
        byte[] bytes = StreamPackCodecService.pack(slot.channel.code(), batch.windowStart(), batch.frames());
        for (int attempt = 0; ; attempt++) {
            try {
                storage.put(key, bytes);
                index(slot, batch.windowStart(), batch.frames().size(), bytes.length, key);
                return;
            } catch (RuntimeException failed) {
                if (attempt >= RETRY_DELAYS_MS.length) {
                    // 사진·키 없이 어느 응시자의 묶음이 빠졌는지만 남긴다 (stream_pack_failed)
                    log.error("stream_pack_failed 회차 {} 대상자 {} 채널 {} 구간 {}: {}", slot.sessionId, slot.participantId,
                            slot.channel.wire(), batch.windowStart(), failed.getClass().getSimpleName());
                    return;
                }
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 색인 행을 만들거나, 같은 구간을 다시 올린 것이면 장수·크기를 갱신한다 */
    private void index(Slot slot, long windowStart, int frameCount, int size, String key) {
        Instant start = Instant.ofEpochMilli(windowStart);
        try {
            var existing = packs.findByParticipantIdAndChannelAndStartAt(slot.participantId, slot.channel, start);
            if (existing.isPresent()) {
                existing.get().reuploaded(frameCount, size);
                packs.save(existing.get());
            } else {
                packs.save(new ProctorStreamPack(slot.sessionId, slot.participantId, slot.channel, start, start.plusMillis(WINDOW_MS),
                        frameCount, size, key, clock.instant()));
            }
        } catch (DataIntegrityViolationException raced) {
            // 같은 구간의 다른 올리기가 먼저 행을 만들었다. 파일은 같은 키로 덮어썼으니 색인만 한 번 더 맞춘다
            packs.findByParticipantIdAndChannelAndStartAt(slot.participantId, slot.channel, start).ifPresent(p -> {
                p.reuploaded(frameCount, size);
                packs.save(p);
            });
        }
    }

    /** 회차 종료 뒤 보관 기간이 지난 묶음 파일을 지우고 기록만 남긴다 (연장 없음). @return 지운 묶음 수 */
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        int total = 0;
        for (Long sessionId : packs.allSessionIdsWithLivePacks()) {
            Instant endsAt = sessions.findById(sessionId).map(ExamSession::getEndsAt).orElse(null);
            if (endsAt == null || endsAt.plus(Duration.ofDays(props.streamRetentionDays())).isAfter(now)) continue;
            List<ProctorStreamPack> batch;
            do {
                batch = packs.findLiveOfSession(sessionId, PageRequest.of(0, PURGE_BATCH));
                for (ProctorStreamPack p : batch) {
                    storage.delete(p.getStorageKey());
                    p.markDeleted(now);
                }
                packs.flush();
                total += batch.size();
            } while (batch.size() == PURGE_BATCH);
        }
        return total;
    }

    private static String key(Long participantId, ProctorStreamPack.Channel channel) {
        return participantId + ":" + channel;
    }

    private void runUpload(Runnable task) {
        try {
            uploader.execute(task);
        } catch (RuntimeException rejected) {
            task.run(); // 종료 중이면 그 자리에서 올린다
        }
    }

    @PreDestroy
    void shutdown() {
        for (Slot slot : slots.values()) flushSlot(slot, true);
        uploader.close();
    }
}
