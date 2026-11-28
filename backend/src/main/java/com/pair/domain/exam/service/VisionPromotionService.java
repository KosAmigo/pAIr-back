package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.exam.dto.StreamFrameDto;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.entity.ProctorEventFrame;
import com.pair.domain.exam.entity.ProctorStreamPack;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorEventFrameRepository;
import com.pair.domain.exam.repository.ProctorEventRepository;
import com.pair.domain.exam.repository.ProctorStreamPackRepository;
import com.pair.global.config.VisionProperties;
import com.pair.infra.storage.SnapshotStorage;

/**
 * 이벤트 구간 승격 (계약 §6-2). 서버 추론 회차의 카메라 이벤트가 저장되고 뒤 4초까지 상시 저장되면, 상시 저장분에서
 * 이벤트 시작 기준 앞 8초·뒤 4초(1초 간격, 채널당 12장)를 뽑아 기존 증거 구간(exam-clips, proctor_event_frames)으로 복사한다.
 * 승격분의 보관·연장·삭제는 기존 규칙(회차의 서버 추론 여부에 따른 기간)을 따른다. 기존 검증(1초 간격·한 장 100KB·채널당 12장·대상자당 100건)을 그대로 적용한다.
 * 백그라운드 점검과 콘솔이 /clip 을 열 때(lazy) 두 곳에서 호출되며, 같은 이벤트를 동시에 승격해도 UNIQUE 제약으로 한 번만 저장된다.
 */
@Service
public class VisionPromotionService {
    private static final Logger log = LoggerFactory.getLogger(VisionPromotionService.class);
    /** 이벤트 시작 기준 앞 8초(포함) ~ 뒤 4초(미포함): 초 단위 -8..3, 12칸 */
    static final int FIRST_SEC = -8;
    static final int LAST_SEC = 3;
    private static final long AFTER_MS = 4_000;
    /** 뒤 4초가 저장되고 마지막 묶음이 올라갈 여유 */
    private static final long SETTLE_MS = 12_000;
    private static final Duration LOOKBACK = Duration.ofMinutes(15);
    private static final int SCAN_LIMIT = 200;

    private final ProctorEventRepository events;
    private final ProctorEventFrameRepository frames;
    private final ProctorStreamPackRepository packs;
    private final ExamParticipantRepository participants;
    private final StreamRecorderService recorder;
    private final SnapshotStorage storage;
    private final VisionProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public VisionPromotionService(ProctorEventRepository events, ProctorEventFrameRepository frames, ProctorStreamPackRepository packs,
            ExamParticipantRepository participants, StreamRecorderService recorder, SnapshotStorage storage, VisionProperties props,
            Clock clock, PlatformTransactionManager txManager) {
        this.events = events;
        this.frames = frames;
        this.packs = packs;
        this.participants = participants;
        this.recorder = recorder;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 승격이 가능해지는 시각 = max(이벤트 수신 시각, 시작 + 4초) + 12초 */
    public Instant promoteAfter(ProctorEvent e) {
        Instant tail = e.getStartedAt().plusMillis(AFTER_MS);
        Instant base = e.getReceivedAt().isAfter(tail) ? e.getReceivedAt() : tail;
        return base.plusMillis(SETTLE_MS);
    }

    /** 승격 후보를 주기적으로 처리한다 (최근 15분 안에 받은 것). 못 뽑은 이벤트도 그동안은 다시 시도한다 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void promoteDue() {
        if (!props.enabled()) return;
        try {
            Instant now = clock.instant();
            for (ProctorEvent e : events.findUnpromoted(now.minus(LOOKBACK), ProctorEvent.Type.cameraTypes(), PageRequest.of(0, SCAN_LIMIT))) {
                if (now.isBefore(promoteAfter(e))) continue;
                try {
                    promote(e.getId());
                } catch (RuntimeException failed) {
                    log.warn("이벤트 구간 승격 실패 (이벤트 {}): {}", e.getId(), failed.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.error("이벤트 구간 승격 점검 실패: {}", e.getClass().getSimpleName());
        }
    }

    /** 이벤트의 구간을 상시 저장분에서 뽑아 복사한다. 이미 있거나 만들 수 없으면 0. @return 새로 만든 사진 수 */
    public int promote(Long eventId) {
        ProctorEvent e = events.findById(eventId).orElse(null);
        if (e == null || !e.getSource().isCamera() || frames.countByEventId(eventId) > 0) return 0;
        recorder.flush(e.getParticipantId()); // 메모리에 모인 최신 사진을 먼저 올린다
        List<String> written = new ArrayList<>();
        try {
            Integer made = tx.execute(status -> copyFrames(e, written));
            return made == null ? 0 : made;
        } catch (DataIntegrityViolationException raced) {
            written.forEach(storage::delete); // 같은 이벤트를 다른 곳이 먼저 승격했다
            return 0;
        } catch (RuntimeException failed) {
            written.forEach(storage::delete);
            throw failed;
        }
    }

    private int copyFrames(ProctorEvent e, List<String> written) {
        ExamParticipant p = participants.findById(e.getParticipantId()).orElse(null);
        if (p == null) return 0;
        ExamSession s = p.getSession();
        if (!s.isServerVision()) return 0;
        if (frames.countClippedEventsOf(p.getId()) >= EvidenceClipService.MAX_CLIPPED_EVENTS_PER_PARTICIPANT) return 0;
        Instant now = clock.instant();
        long eventMs = e.getStartedAt().toEpochMilli();
        long eventSec = Math.floorDiv(eventMs, 1000L);
        int made = 0;
        for (ProctorStreamPack.Channel channel : ProctorStreamPack.Channel.values()) {
            if (channel == ProctorStreamPack.Channel.PHONE && !props.phoneViewable()) continue; // 휴대폰 사진 열람을 막은 설정이면 승격하지 않는다
            Map<Integer, byte[]> chosen = pick(channel, p.getId(), eventMs, eventSec);
            for (Map.Entry<Integer, byte[]> f : chosen.entrySet()) {
                byte[] jpeg = f.getValue();
                ProctorEventFrame row = frames.saveAndFlush(new ProctorEventFrame(s.getId(), e.getId(), channel.source(),
                        f.getKey() * 1000, "pending", jpeg.length, now));
                String key = SnapshotStorage.clipKeyOf(s.getId(), e.getId(), row.getId());
                storage.put(key, jpeg);
                written.add(key);
                row.assignKey(key);
                made++;
            }
        }
        return made;
    }

    /** 채널의 상시 저장 묶음에서 앞 8초·뒤 4초의 초마다 한 장을 고른다 (초 오프셋 → 사진, 한 장 100KB 이하만) */
    private Map<Integer, byte[]> pick(ProctorStreamPack.Channel channel, Long participantId, long eventMs, long eventSec) {
        Map<Integer, byte[]> chosen = new TreeMap<>();
        Instant from = Instant.ofEpochMilli(eventMs + FIRST_SEC * 1000L);
        Instant to = Instant.ofEpochMilli(eventMs + (LAST_SEC + 1) * 1000L);
        for (ProctorStreamPack pack : packs.findOverlapping(participantId, channel, from, to)) {
            List<StreamFrameDto> list;
            try {
                list = StreamPackCodecService.unpack(storage.get(pack.getStorageKey()).orElse(new byte[0]));
            } catch (RuntimeException skip) {
                log.warn("상시 저장 묶음을 읽지 못해 건너뜁니다 (묶음 {}): {}", pack.getId(), skip.getClass().getSimpleName());
                continue;
            }
            for (StreamFrameDto f : list) {
                int sec = (int) (Math.floorDiv(f.atMs(), 1000L) - eventSec);
                if (sec < FIRST_SEC || sec > LAST_SEC || f.jpeg().length > EvidenceClipService.MAX_FRAME_BYTES) continue;
                chosen.putIfAbsent(sec, f.jpeg());
            }
        }
        return chosen;
    }
}
