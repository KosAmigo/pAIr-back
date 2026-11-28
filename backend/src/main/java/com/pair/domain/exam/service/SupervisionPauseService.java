package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.exam.entity.ProctorEvent;
import com.pair.domain.exam.repository.ProctorEventRepository;

/**
 * 감독 중단(SUPERVISION_PAUSED) 이벤트를 만들고 닫는다 (계약 §8). 서버가 직접 남기는 정보용 기록이라 사진이 없고 확정·기각 대상이 아니다.
 * clientId 는 "p" + 대상자 번호 + "-" + 시작 초 (결정적). 진행 중인 중단은 주기적으로 지속 시간을 갱신하고, 복구되면 확정한다.
 * 이 기록이 있는 구간의 사진(상시 저장분)은 콘솔에서 시각 범위로 열 수 있다.
 */
@Service
public class SupervisionPauseService {
    private static final Logger log = LoggerFactory.getLogger(SupervisionPauseService.class);

    /** 진행 중인 중단: 이벤트 번호와 시작 시각 */
    private record Open(Long eventId, Instant startedAt) {}

    private final ProctorEventRepository events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<Long, Open> open = new ConcurrentHashMap<>();

    public SupervisionPauseService(ProctorEventRepository events, PlatformTransactionManager txManager, Clock clock) {
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 이 응시자의 감독 중단을 시작한다 (이미 진행 중이면 그대로). reason: VISION_DOWN, NO_STATE, NO_CAPACITY */
    public void open(Long participantId, String reason) {
        if (open.containsKey(participantId)) return;
        Instant now = clock.instant();
        String clientId = "p" + participantId + "-" + now.getEpochSecond();
        try {
            Long id = tx.execute(status -> {
                Optional<ProctorEvent> existing = events.findByParticipantIdAndClientId(participantId, clientId);
                if (existing.isPresent()) return existing.get().getId();
                ProctorEvent e = new ProctorEvent(participantId, ProctorEvent.Type.SUPERVISION_PAUSED, now, 0, null,
                        "{\"reason\":\"" + reason + "\"}", now);
                e.assignClientId(clientId);
                return events.saveAndFlush(e).getId();
            });
            open.put(participantId, new Open(id, now));
        } catch (DataIntegrityViolationException | IllegalStateException failed) {
            log.warn("감독 중단 기록 실패 (대상자 {}): {}", participantId, failed.getClass().getSimpleName());
        }
    }

    /** 복구됐거나 연결이 끝나서 중단을 닫는다. 지속 시간을 확정한다 */
    public void close(Long participantId) {
        Open o = open.remove(participantId);
        if (o != null) updateDuration(o, clock.instant());
    }

    /** 진행 중인 중단의 지속 시간을 지금까지로 갱신한다 (콘솔이 진행 상황을 보게) */
    public void refresh() {
        Instant now = clock.instant();
        for (Open o : List.copyOf(open.values())) updateDuration(o, now);
    }

    public boolean isOpen(Long participantId) {
        return open.containsKey(participantId);
    }

    /** 이 이벤트가 아직 진행 중인 중단인지 */
    public boolean isOpenEvent(Long eventId) {
        return open.values().stream().anyMatch(o -> o.eventId().equals(eventId));
    }

    private void updateDuration(Open o, Instant now) {
        long ms = Math.max(0, Math.min(Integer.MAX_VALUE, now.toEpochMilli() - o.startedAt().toEpochMilli()));
        try {
            tx.executeWithoutResult(status -> events.findById(o.eventId()).ifPresent(e -> e.extendDurationTo((int) ms)));
        } catch (RuntimeException failed) {
            log.warn("감독 중단 지속 시간 갱신 실패 (이벤트 {}): {}", o.eventId(), failed.getClass().getSimpleName());
        }
    }
}
