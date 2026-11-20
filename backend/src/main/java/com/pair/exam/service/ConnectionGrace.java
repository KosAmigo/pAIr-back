package com.pair.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ProctorEvent;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.repository.ProctorEventRepository;

/**
 * 시험 중 연결 유예. 시작한 응시자의 시험 화면은 10초마다 신호를 보낸다 (heartbeat).
 * - 신호가 끊겼다 90초 넘어 돌아오면 CONNECTION_LOST (끊긴 시간과 함께) 를 남긴다.
 * - 마지막 신호 뒤 5분 안에 돌아오지 못하면 입장 링크를 만료시키고 LINK_EXPIRED 를 남긴다.
 * 돌아오지 않는 경우도 주기 점검(expireAbsent)이 기록을 남긴다. 자동 탈락은 없고 감독 담당자가 확인하는 기록이다.
 */
@Component
public class ConnectionGrace {
    static final Duration GRACE = Duration.ofMinutes(5);
    /** 이보다 오래 신호가 없다 돌아오면 "끊김"으로 본다 (잠깐의 네트워크 지연·브라우저 타이머 지연은 넘긴다) */
    static final Duration GAP = Duration.ofSeconds(90);

    private final ExamParticipantRepository participants;
    private final ProctorEventRepository events;
    private final ParticipantProgress progress;
    private final Clock clock;

    public ConnectionGrace(ExamParticipantRepository participants, ProctorEventRepository events, ParticipantProgress progress, Clock clock) {
        this.participants = participants;
        this.events = events;
        this.progress = progress;
        this.clock = clock;
    }

    /** 이 대상자의 링크가 유예를 넘겨 쓸 수 없는 상태인가 (아직 기록되지 않았어도 판단한다) */
    public static boolean isExpired(ExamParticipant p, boolean started, boolean finished, Instant now) {
        if (p.getLinkExpiredAt() != null) return true;
        return started && !finished && p.getLastSeenAt() != null && Duration.between(p.getLastSeenAt(), now).compareTo(GRACE) > 0;
    }

    /**
     * 시작한 응시자가 다시 연결됐을 때 (재입장, 신호). 호출하는 쪽의 트랜잭션 안에서 부른다.
     * 만료면 기록을 남기고 LinkExpiredException 을 던진다 (호출하는 쪽은 noRollbackFor 로 기록을 지키거나 예외를 잡는다).
     */
    void touch(ExamParticipant p, Instant now) {
        if (p.getLinkExpiredAt() != null) throw new LinkExpiredException();
        Instant last = p.getLastSeenAt();
        if (last != null) {
            Duration gap = Duration.between(last, now);
            if (gap.compareTo(GRACE) > 0) {
                expire(p, last, now);
                throw new LinkExpiredException();
            }
            if (gap.compareTo(GAP) > 0) {
                events.save(new ProctorEvent(p.getId(), ProctorEvent.Type.CONNECTION_LOST, last, (int) gap.toMillis(), null,
                        "{\"gapSeconds\":" + gap.toSeconds() + "}", now));
            }
        }
        p.markSeen(now);
    }

    private void expire(ExamParticipant p, Instant last, Instant now) {
        p.expireLink(now);
        events.save(new ProctorEvent(p.getId(), ProctorEvent.Type.LINK_EXPIRED, last.plus(GRACE), (int) Duration.between(last, now).toMillis(), null,
                "{\"graceMinutes\":" + GRACE.toMinutes() + "}", now));
    }

    /** 주기 점검: 진행 중인 회차에서 마지막 신호가 유예를 넘긴 응시자를 만료시킨다. 돌아오지 않아도 기록이 남게 한다. @return 만료시킨 인원 */
    @Transactional
    public int expireAbsent() {
        Instant now = clock.instant();
        List<ExamParticipant> late = participants.findAbsentSince(now.minus(GRACE), now);
        int n = 0;
        for (ExamParticipant p : late) {
            if (progress.of(p).finished()) continue; // 이미 다 제출한 응시자는 대상이 아니다
            expire(p, p.getLastSeenAt(), now);
            n++;
        }
        return n;
    }
}
