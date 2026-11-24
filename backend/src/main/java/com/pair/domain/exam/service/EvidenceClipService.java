package com.pair.domain.exam.service;

import com.pair.global.security.InviteTokenService;
import com.pair.infra.storage.SnapshotStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.entity.ProctorEventFrame;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorEventFrameRepository;
import com.pair.domain.exam.repository.ProctorEventRepository;
import com.pair.domain.exam.dto.ClipFrameRequest;
import com.pair.domain.exam.dto.ClipFrameDto;
import com.pair.domain.exam.dto.ClipUploadRequest;
import com.pair.domain.exam.dto.ClipResponse;

/**
 * 이벤트 증거 구간: 이벤트가 난 뒤 응시자 PC 가 자기 링 버퍼(앞 8초·뒤 4초)에서 골라 올린 사진을 파일 저장소에 두고 콘솔에서 보여 준다.
 * 서버는 분석하지 않고 연속 녹화도 받지 않는다. 사진은 이벤트마다 최대 24장, 대상자마다 증거 구간이 붙은 이벤트는 최대 100건.
 * 보관·삭제는 감독 사진과 같은 규칙(회차 종료 7일, 연장 반영)을 따른다 (ProctorService.purgeExpiredSnapshots).
 */
@Service
public class EvidenceClipService {

    public static final int MAX_FRAMES_PER_EVENT = 24;
    /** 합계 24장 = 채널(PC 웹캠, 휴대폰)마다 최대 12장 */
    public static final int MAX_FRAMES_PER_CHANNEL = 12;
    /** 같은 채널의 사진은 1초 이상 간격 */
    public static final int MIN_GAP_MS = 1000;
    /** 사진 한 장 최대 크기 */
    public static final int MAX_FRAME_BYTES = 100_000;
    /** 요청 본문(base64 문자 수 합) 상한 2MB */
    public static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    public static final int MAX_CLIPPED_EVENTS_PER_PARTICIPANT = 100;
    public static final int MIN_OFFSET_MS = -8000;
    public static final int MAX_OFFSET_MS = 4000;
    private static final Duration LATE_GRACE = Duration.ofMinutes(30);

    private final ExamParticipantRepository participants;
    private final ProctorEventRepository events;
    private final ProctorEventFrameRepository frames;
    private final SnapshotValidationService validator;
    private final SnapshotStorage storage;
    private final Clock clock;

    public EvidenceClipService(ExamParticipantRepository participants, ProctorEventRepository events, ProctorEventFrameRepository frames,
            SnapshotValidationService validator, SnapshotStorage storage, Clock clock) {
        this.participants = participants;
        this.events = events;
        this.frames = frames;
        this.validator = validator;
        this.storage = storage;
        this.clock = clock;
    }

    /** 응시자 PC 가 이벤트(clientId)에 증거 사진을 올린다. 같은 (출처, 시점)은 한 장만 있고 다시 보내면 건너뛴다 */
    @Transactional
    public void upload(String token, String clientId, ClipUploadRequest req) {
        if (!InviteTokenService.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        ExamParticipant p = participants.findByInviteTokenHash(InviteTokenService.hash(token))
                .orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
        ExamSession s = p.getSession();
        if (!s.isProctoring()) throw new IllegalStateException("감독을 쓰지 않는 회차입니다.");
        Instant now = clock.instant();
        if (now.isAfter(s.getEndsAt().plus(LATE_GRACE))) throw new IllegalStateException("끝난 시험의 증거 사진은 받지 않습니다.");
        ProctorEvent event = events.findByParticipantIdAndClientId(p.getId(), clientId)
                .orElseThrow(() -> new NoSuchElementException("없는 이벤트입니다."));
        if (!event.getSource().isCamera()) throw new IllegalArgumentException("카메라 이벤트에만 증거 사진을 붙입니다.");

        List<ClipFrameRequest> list = req.frames();
        if (list.size() > MAX_FRAMES_PER_EVENT) throw new IllegalArgumentException("증거 사진은 이벤트마다 최대 " + MAX_FRAMES_PER_EVENT + "장입니다.");
        for (ClipFrameRequest f : list) {
            if (!f.source().isCamera()) throw new IllegalArgumentException("증거 사진은 카메라(PC 웹캠·휴대폰)에서만 받습니다.");
            if (f.offsetMs() < MIN_OFFSET_MS || f.offsetMs() > MAX_OFFSET_MS) throw new IllegalArgumentException("증거 구간(이벤트 앞 8초~뒤 4초) 밖의 시각입니다.");
        }

        long bodyChars = 0;
        for (ClipFrameRequest f : list) bodyChars += f.image().length();
        if (bodyChars > MAX_BODY_BYTES) throw new IllegalArgumentException("증거 사진 요청은 2MB 를 넘을 수 없습니다. 나눠서 보내 주세요.");

        Set<String> existing = new HashSet<>();
        Map<ProctorEvent.Source, TreeSet<Integer>> offsets = new EnumMap<>(ProctorEvent.Source.class);
        frames.findByEventIdOrderByOffsetMsAscSourceAsc(event.getId()).forEach(f -> {
            existing.add(idOf(f.getSource(), f.getOffsetMs()));
            offsets.computeIfAbsent(f.getSource(), k -> new TreeSet<>()).add(f.getOffsetMs());
        });
        Set<String> incoming = new HashSet<>();
        for (ClipFrameRequest f : list) {
            if (existing.contains(idOf(f.source(), f.offsetMs()))) continue;
            incoming.add(idOf(f.source(), f.offsetMs()));
            offsets.computeIfAbsent(f.source(), k -> new TreeSet<>()).add(f.offsetMs());
        }
        if (existing.size() + incoming.size() > MAX_FRAMES_PER_EVENT) {
            throw new IllegalArgumentException("증거 사진은 이벤트마다 최대 " + MAX_FRAMES_PER_EVENT + "장입니다.");
        }
        // 합계 24장 = 채널(웹캠·휴대폰)마다 최대 12장, 같은 채널의 사진은 1초 이상 떨어져 있어야 한다
        for (TreeSet<Integer> channel : offsets.values()) {
            if (channel.size() > MAX_FRAMES_PER_CHANNEL) throw new IllegalArgumentException("증거 사진은 채널마다 최대 " + MAX_FRAMES_PER_CHANNEL + "장입니다.");
            Integer prev = null;
            for (int at : channel) {
                if (prev != null && at - prev < MIN_GAP_MS) throw new IllegalArgumentException("같은 채널의 사진은 1초 이상 간격이어야 합니다.");
                prev = at;
            }
        }
        if (existing.isEmpty() && !incoming.isEmpty() && frames.countClippedEventsOf(p.getId()) >= MAX_CLIPPED_EVENTS_PER_PARTICIPANT) {
            throw new IllegalStateException("증거 구간이 붙은 이벤트가 너무 많습니다.");
        }

        List<String> written = new ArrayList<>();
        // 커밋이 실패해 되돌려지면 올린 파일이 고아로 남으므로, 트랜잭션이 롤백으로 끝날 때 치운다
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) written.forEach(storage::delete);
                    }
                });
        try {
            Set<String> done = new HashSet<>();
            for (ClipFrameRequest f : list) {
                String id = idOf(f.source(), f.offsetMs());
                if (existing.contains(id) || !done.add(id)) continue;
                byte[] jpeg = validator.decode(f.image(), MAX_FRAME_BYTES);
                ProctorEventFrame row = frames.saveAndFlush(
                        new ProctorEventFrame(s.getId(), event.getId(), f.source(), f.offsetMs(), "pending", jpeg.length, now));
                String key = SnapshotStorage.clipKeyOf(s.getId(), event.getId(), row.getId());
                storage.put(key, jpeg);
                written.add(key);
                row.assignKey(key);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            throw new IllegalStateException("같은 증거 사진을 다른 요청이 올리는 중입니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    @Transactional(readOnly = true)
    public ClipResponse clipOf(Long sessionId, Long eventId) {
        requireEventIn(sessionId, eventId);
        return new ClipResponse(eventId, frames.findByEventIdOrderByOffsetMsAscSourceAsc(eventId).stream()
                .map(f -> new ClipFrameDto(f.getId(), f.getSource(), f.getOffsetMs(), f.getSizeBytes(), f.getDeletedAt() != null)).toList());
    }

    @Transactional(readOnly = true)
    public byte[] frameImage(Long sessionId, Long eventId, Long frameId) {
        requireEventIn(sessionId, eventId);
        ProctorEventFrame f = frames.findById(frameId).filter(x -> x.getEventId().equals(eventId))
                .orElseThrow(() -> new NoSuchElementException("없는 사진입니다."));
        if (f.getDeletedAt() != null) throw new IllegalStateException("보관 기간이 지나 삭제된 사진입니다.");
        return storage.get(f.getStorageKey()).orElseThrow(() -> new NoSuchElementException("사진 파일이 없습니다."));
    }

    /** 회차의 이벤트별 증거 사진 수 (콘솔 타임라인이 증거 구간이 있는 이벤트를 표시하려고 한 번에 받는다) */
    @Transactional(readOnly = true)
    public Map<Long, Long> countsOfSession(Long sessionId) {
        Map<Long, Long> out = new HashMap<>();
        frames.countBySession(sessionId).forEach(c -> out.put(c.getEventId(), c.getCount()));
        return out;
    }

    private static String idOf(ProctorEvent.Source source, int offsetMs) {
        return source + "/" + offsetMs;
    }

    /** 다른 회차 번호로는 이벤트의 사진을 볼 수 없다 */
    private void requireEventIn(Long sessionId, Long eventId) {
        ProctorEvent e = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("없는 이벤트입니다."));
        ExamParticipant p = participants.findById(e.getParticipantId()).orElseThrow(() -> new NoSuchElementException("없는 이벤트입니다."));
        if (!p.getSession().getId().equals(sessionId)) throw new NoSuchElementException("없는 이벤트입니다.");
    }
}
