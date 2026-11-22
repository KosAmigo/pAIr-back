package com.pair.exam.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.domain.ProctorNotice;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.repository.ProctorNoticeRepository;
import com.pair.exam.web.ExamDtos.NoticeView;

/**
 * 콘솔이 응시자에게 보내는 안내 메시지 (일방향, 자유 입력). 시험 전·중 모두 보낼 수 있다.
 * 응시자는 확인(읽음)만 누를 수 있고 답장은 없다.
 */
@Service
public class NoticeService {

    public static final int MAX_LENGTH = 500;

    private final ExamParticipantRepository participants;
    private final ProctorNoticeRepository notices;
    private final Clock clock;

    public NoticeService(ExamParticipantRepository participants, ProctorNoticeRepository notices, Clock clock) {
        this.participants = participants;
        this.notices = notices;
        this.clock = clock;
    }

    @Transactional
    public NoticeView send(Long sessionId, Long participantId, String message, Long senderId) {
        Instant now = clock.instant();
        ExamParticipant p = inSession(sessionId, participantId);
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("안내 내용을 입력해 주세요.");
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("안내는 " + MAX_LENGTH + "자까지 보낼 수 있습니다.");
        ExamSession s = p.getSession();
        if (s.getCanceledAt() != null) throw new IllegalStateException("취소된 회차에는 안내를 보낼 수 없습니다.");
        if (now.isAfter(s.getEndsAt())) throw new IllegalStateException("끝난 회차에는 안내를 보낼 수 없습니다.");
        return NoticeView.from(notices.save(new ProctorNotice(p.getId(), text, senderId, now)));
    }

    /** 콘솔: 이 대상자에게 보낸 메시지와 읽음 여부 (보낸 순서) */
    @Transactional(readOnly = true)
    public List<NoticeView> listOf(Long sessionId, Long participantId) {
        ExamParticipant p = inSession(sessionId, participantId);
        return notices.findByParticipantIdOrderByIdAsc(p.getId()).stream().map(NoticeView::from).toList();
    }

    /** 응시자: 내가 받은 메시지 (끝난 뒤에도 읽을 수 있다) */
    @Transactional(readOnly = true)
    public List<NoticeView> forEntry(String token) {
        return notices.findByParticipantIdOrderByIdAsc(byToken(token).getId()).stream().map(NoticeView::from).toList();
    }

    /** 응시자가 확인을 누름. 이미 읽은 메시지는 처음 읽은 시각을 지킨다. 남의 메시지는 없는 것으로 본다 */
    @Transactional
    public void markRead(String token, Long noticeId) {
        ExamParticipant p = byToken(token);
        ProctorNotice n = notices.findById(noticeId)
                .filter(x -> x.getParticipantId().equals(p.getId()))
                .orElseThrow(() -> new NoSuchElementException("없는 안내입니다."));
        n.markRead(clock.instant());
    }

    private ExamParticipant inSession(Long sessionId, Long participantId) {
        return participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
    }

    private ExamParticipant byToken(String token) {
        if (!InviteTokens.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.findByInviteTokenHash(InviteTokens.hash(token))
                .orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }
}
