package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.repository.ExamParticipantRepository;

/**
 * 시작 하루 전 알림 메일. 스케줄러가 주기적으로 부른다.
 * 먼저 reminded_at 을 찍어 커밋하고(한 번만 보내도록 자리를 잡음) 그다음 메일을 보낸다.
 * 그래서 메일이 실패해도 다시 보내지 않는다 (알림은 덤이고, 입장 링크는 초대 메일에 있다).
 */
@Service
public class ReminderService {

    static final Duration BEFORE_START = Duration.ofHours(24);
    private static final int BATCH = 500;

    private final ExamParticipantRepository participants;
    private final Clock clock;

    public ReminderService(ExamParticipantRepository participants, Clock clock) {
        this.participants = participants;
        this.clock = clock;
    }

    /** @return 알림을 보낼 대상자 ID (이미 reminded_at 을 찍었다) */
    @Transactional
    public List<Long> claimDue() {
        Instant now = clock.instant();
        List<Long> due = new ArrayList<>();
        long afterId = 0;
        List<ExamParticipant> page;
        do {
            page = participants.findReminderCandidates(now, now.plus(BEFORE_START), afterId, PageRequest.of(0, BATCH));
            for (ExamParticipant p : page) {
                afterId = p.getId();
                // 시작 24시간 안에 초대된 사람은 초대 메일이 곧 알림이다
                if (p.getInvitedAt().isAfter(p.getSession().getStartsAt().minus(BEFORE_START))) continue;
                due.add(p.getId());
            }
        } while (page.size() == BATCH);
        // 엔티티를 고치지 않고 reminded_at 만 바꾼다 (그 사이 입장한 기록을 덮어쓰지 않게)
        if (!due.isEmpty()) participants.markReminded(due, now);
        return due;
    }
}
