package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.dto.StreamFrameDto;
import com.pair.domain.exam.dto.StreamListResponse;
import com.pair.domain.exam.dto.StreamTimeDto;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.entity.ProctorStreamAccessLog;
import com.pair.domain.exam.entity.ProctorStreamPack;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorEventRepository;
import com.pair.domain.exam.repository.ProctorStreamAccessLogRepository;
import com.pair.domain.exam.repository.ProctorStreamPackRepository;
import com.pair.global.config.VisionProperties;
import com.pair.global.error.CodedException;
import com.pair.infra.storage.SnapshotStorage;

/**
 * 감독 중단 구간의 상시 저장 사진 열람 (계약 §6-5). AI 판정이 없던 구간을 감독관이 사진으로 직접 확인한다.
 * 열람 범위는 그 대상자의 SUPERVISION_PAUSED 이벤트가 가리키는 시각 범위뿐이다(밖이면 403 OUT_OF_ALLOWED_RANGE). 상시 저장분 전체는 열람할 수 없다.
 * 목록·사진을 조회할 때마다 열람 기록(누가·언제·대상자·채널·범위·건수)을 남기며 감독관은 이 기록을 지우지 못한다.
 * 휴대폰 채널은 phone-viewable 설정으로 막을 수 있다(사용자 확인 대기 중인 가정).
 */
@Service
public class VisionStreamViewService {
    private static final Logger log = LoggerFactory.getLogger(VisionStreamViewService.class);
    public static final int DEFAULT_LIMIT = 120;
    public static final int MAX_LIMIT = 300;

    /** 열 수 있는 시각 범위와 채널 */
    private record Allowed(ExamParticipant participant, ProctorStreamPack.Channel channel, Instant start, Instant end) {}

    private final ExamParticipantRepository participants;
    private final ProctorEventRepository events;
    private final ProctorStreamPackRepository packs;
    private final ProctorStreamAccessLogRepository accessLogs;
    private final SupervisionPauseService pauses;
    private final VisionSessionService vision;
    private final SnapshotStorage storage;
    private final VisionProperties props;
    private final Clock clock;

    public VisionStreamViewService(ExamParticipantRepository participants, ProctorEventRepository events, ProctorStreamPackRepository packs,
            ProctorStreamAccessLogRepository accessLogs, SupervisionPauseService pauses, VisionSessionService vision, SnapshotStorage storage,
            VisionProperties props, Clock clock) {
        this.participants = participants;
        this.events = events;
        this.packs = packs;
        this.accessLogs = accessLogs;
        this.pauses = pauses;
        this.vision = vision;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public StreamListResponse list(Long sessionId, Long participantId, Long eventId, String channelWire, Instant from, Integer limitParam, Long viewerId) {
        Allowed a = allowed(sessionId, participantId, eventId, channelWire);
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limitParam));
        Instant start = from == null ? a.start() : from;
        if (start.isBefore(a.start()) || start.isAfter(a.end())) throw outOfRange();

        List<StreamTimeDto> found = new ArrayList<>();
        for (ProctorStreamPack pack : packs.findOverlapping(participantId, a.channel(), start, a.end().plusMillis(1))) {
            for (StreamFrameDto f : read(pack)) {
                Instant t = Instant.ofEpochMilli(f.atMs());
                if (t.isBefore(start) || t.isAfter(a.end())) continue;
                found.add(new StreamTimeDto(t, 1));
            }
            if (found.size() > limit) break;
        }
        Instant next = found.size() > limit ? found.get(limit).t() : null;
        List<StreamTimeDto> page = found.size() > limit ? List.copyOf(found.subList(0, limit)) : List.copyOf(found);
        Instant last = page.isEmpty() ? start : page.get(page.size() - 1).t();
        logAccess(sessionId, participantId, eventId, viewerId, a.channel(), start, last, page.size());
        return new StreamListResponse(eventId, a.channel().wire(), start, a.end(), page, next);
    }

    /** 사진 한 장 (t 는 목록의 값). 목록에 없는 시각이면 404 */
    @Transactional
    public byte[] frame(Long sessionId, Long participantId, Long eventId, String channelWire, Instant t, Long viewerId) {
        Allowed a = allowed(sessionId, participantId, eventId, channelWire);
        if (t.isBefore(a.start()) || t.isAfter(a.end())) throw outOfRange();
        for (ProctorStreamPack pack : packs.findOverlapping(participantId, a.channel(), t, t.plusMillis(1))) {
            for (StreamFrameDto f : read(pack)) {
                if (f.atMs() == t.toEpochMilli()) {
                    logAccess(sessionId, participantId, eventId, viewerId, a.channel(), t, t, 1);
                    return f.jpeg();
                }
            }
        }
        throw new NoSuchElementException("그 시각의 사진이 없습니다.");
    }

    private Allowed allowed(Long sessionId, Long participantId, Long eventId, String channelWire) {
        ExamParticipant p = participants.findById(participantId).filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        ExamSession s = p.getSession();
        if (!vision.isActive(s)) throw new NoSuchElementException("서버 추론 감독을 쓰지 않는 회차입니다."); // 적용 조건이 거짓이면 404
        ProctorEvent e = events.findById(eventId).filter(x -> x.getParticipantId().equals(participantId))
                .orElseThrow(() -> new NoSuchElementException("없는 이벤트입니다."));
        if (e.getType() != ProctorEvent.Type.SUPERVISION_PAUSED) throw outOfRange();
        ProctorStreamPack.Channel channel = ProctorStreamPack.Channel.ofWire(channelWire == null ? "webcam" : channelWire)
                .orElseThrow(() -> new IllegalArgumentException("channel 은 webcam 또는 phone 입니다."));
        if (channel == ProctorStreamPack.Channel.PHONE && !props.phoneViewable()) {
            throw new CodedException(HttpStatus.FORBIDDEN, "PHONE_NOT_VIEWABLE", "휴대폰 사진은 열람할 수 없게 설정되어 있습니다.");
        }
        Instant now = clock.instant();
        if (!now.isBefore(s.getEndsAt().plus(Duration.ofDays(props.streamRetentionDays())))) {
            throw new CodedException(HttpStatus.GONE, "EXPIRED", "보관 기간이 지나 삭제된 사진입니다.");
        }
        Instant end = pauses.isOpenEvent(eventId) ? now : e.getStartedAt().plusMillis(e.getDurationMs());
        return new Allowed(p, channel, e.getStartedAt(), end);
    }

    private List<StreamFrameDto> read(ProctorStreamPack pack) {
        try {
            return StreamPackCodecService.unpack(storage.get(pack.getStorageKey()).orElse(new byte[0]));
        } catch (RuntimeException unreadable) {
            log.warn("상시 저장 묶음을 읽지 못해 건너뜁니다 (묶음 {}): {}", pack.getId(), unreadable.getClass().getSimpleName());
            return List.of();
        }
    }

    private void logAccess(Long sessionId, Long participantId, Long eventId, Long viewerId, ProctorStreamPack.Channel channel,
            Instant from, Instant to, int count) {
        accessLogs.save(new ProctorStreamAccessLog(sessionId, participantId, eventId, viewerId, channel.name(), from, to, count, clock.instant()));
    }

    private static CodedException outOfRange() {
        return new CodedException(HttpStatus.FORBIDDEN, "OUT_OF_ALLOWED_RANGE", "감독 중단 구간 밖의 사진은 열 수 없습니다.");
    }
}
