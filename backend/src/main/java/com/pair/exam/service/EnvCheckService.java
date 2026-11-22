package com.pair.exam.service;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.exam.domain.EnvCheckProgress;
import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.repository.EnvCheckProgressRepository;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.repository.ProctorConsentRepository;
import com.pair.exam.web.ExamDtos.EnvCheckSource;
import com.pair.exam.web.ExamDtos.EnvCheckView;

/**
 * 환경 점검 콘솔 확인. 응시자 브라우저가 점검 중 상태를 올리면(진행 상태) 콘솔이 실시간으로 보고,
 * 동의와 함께 저장된 결과(ProctorConsent.envCheck)와 비교해 더 최근 것을 보여 준다.
 */
@Service
public class EnvCheckService {

    /** 진행 상태 JSON 최대 길이 */
    public static final int MAX_CHARS = 20_000;

    private final ExamParticipantRepository participants;
    private final EnvCheckProgressRepository progress;
    private final ProctorConsentRepository consents;
    private final ObjectMapper json;
    private final Clock clock;

    public EnvCheckService(ExamParticipantRepository participants, EnvCheckProgressRepository progress,
            ProctorConsentRepository consents, ObjectMapper json, Clock clock) {
        this.participants = participants;
        this.progress = progress;
        this.consents = consents;
        this.json = json;
        this.clock = clock;
    }

    /** 응시자 브라우저가 올리는 점검 진행 상태. 대상자당 최신 1건을 덮어쓴다 */
    @Transactional
    public void saveProgress(String token, JsonNode result) {
        Instant now = clock.instant();
        if (!InviteTokens.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        ExamParticipant p = participants.findByInviteTokenHash(InviteTokens.hash(token))
                .orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
        ExamSession s = p.getSession();
        if (!s.isProctoring()) throw new IllegalStateException("감독을 쓰지 않는 회차입니다.");
        if (s.getCanceledAt() != null) throw new IllegalStateException("취소된 회차입니다.");
        if (now.isAfter(s.getEndsAt())) throw new IllegalStateException("끝난 시험의 점검 상태는 받지 않습니다.");
        String text = write(result);
        progress.findById(p.getId()).ifPresentOrElse(
                existing -> existing.update(text, now),
                () -> progress.save(new EnvCheckProgress(p.getId(), text, now)));
    }

    /** 콘솔에서 보는 점검 결과. 저장 결과와 진행 상태 중 더 최근 것. 둘 다 없으면 null */
    @Transactional(readOnly = true)
    public EnvCheckView view(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        var live = progress.findById(p.getId());
        var saved = consents.findById(p.getId());
        if (live.isEmpty() && saved.isEmpty()) return null;
        boolean useLive = live.isPresent()
                && (saved.isEmpty() || live.get().getUpdatedAt().isAfter(saved.get().getConsentedAt()));
        return useLive
                ? new EnvCheckView(EnvCheckSource.LIVE, live.get().getUpdatedAt(), read(live.get().getResult()))
                : new EnvCheckView(EnvCheckSource.SAVED, saved.get().getConsentedAt(), read(saved.get().getEnvCheck()));
    }

    private String write(JsonNode result) {
        try {
            String text = json.writeValueAsString(result);
            if (text.length() > MAX_CHARS) throw new IllegalArgumentException("환경 점검 상태가 너무 깁니다.");
            return text;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("환경 점검 상태를 읽지 못했습니다.");
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 환경 점검 결과를 읽지 못했습니다.");
        }
    }
}
