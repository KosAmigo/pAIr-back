package com.pair.domain.exam.service;

import com.pair.global.security.InviteTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.exam.entity.EnvCheckProgress;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.EnvCheckProgressRepository;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorConsentRepository;
import com.pair.domain.exam.dto.EnvCheckFailureResponse;
import com.pair.domain.exam.dto.EnvCheckResponse;
import com.pair.domain.exam.entity.ProctorConsent;
import com.pair.domain.exam.repository.ProctorNoticeRepository;

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
    private final ProctorNoticeRepository notices;
    private final ObjectMapper json;
    private final Clock clock;

    public EnvCheckService(ExamParticipantRepository participants, EnvCheckProgressRepository progress,
            ProctorConsentRepository consents, ProctorNoticeRepository notices, ObjectMapper json, Clock clock) {
        this.participants = participants;
        this.progress = progress;
        this.consents = consents;
        this.notices = notices;
        this.json = json;
        this.clock = clock;
    }

    /** 응시자 브라우저가 올리는 점검 진행 상태. 대상자당 최신 1건을 덮어쓴다 */
    @Transactional
    public void saveProgress(String token, JsonNode result) {
        Instant now = clock.instant();
        if (!InviteTokenService.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        ExamParticipant p = participants.findByInviteTokenHash(InviteTokenService.hash(token))
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
    public EnvCheckResponse view(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        var live = progress.findById(p.getId());
        var saved = consents.findById(p.getId());
        if (live.isEmpty() && saved.isEmpty()) return null;
        boolean useLive = live.isPresent()
                && (saved.isEmpty() || live.get().getUpdatedAt().isAfter(saved.get().getConsentedAt()));
        return useLive
                ? new EnvCheckResponse(EnvCheckResponse.Source.LIVE, live.get().getUpdatedAt(), read(live.get().getResult()))
                : new EnvCheckResponse(EnvCheckResponse.Source.SAVED, saved.get().getConsentedAt(), read(saved.get().getEnvCheck()));
    }

    /**
     * 환경 점검에서 실패한 항목이 있는데도 입장한 응시자 모음 (콘솔 "점검 미통과 입장자" 탭).
     * 입장할 때 동의와 함께 저장된 점검 결과(PC·휴대폰 항목)에서 status 가 fail 인 항목을 모은다. 입장하지 않았거나 실패가 없으면 제외한다.
     */
    @Transactional(readOnly = true)
    public List<EnvCheckFailureResponse> failuresOf(Long sessionId) {
        List<ExamParticipant> entered = participants.findBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(p -> p.getEnteredAt() != null).toList();
        if (entered.isEmpty()) return List.of();
        List<Long> ids = entered.stream().map(ExamParticipant::getId).toList();
        Map<Long, ProctorConsent> saved = new HashMap<>();
        consents.findAllById(ids).forEach(c -> saved.put(c.getParticipantId(), c));
        Set<Long> noticed = new HashSet<>(notices.findParticipantIdsWithNotice(ids));
        List<EnvCheckFailureResponse> out = new ArrayList<>();
        for (ExamParticipant p : entered) {
            ProctorConsent consent = saved.get(p.getId());
            if (consent == null) continue;
            List<EnvCheckFailureResponse.Item> failed = failedItems(read(consent.getEnvCheck()));
            if (failed.isEmpty()) continue;
            out.add(new EnvCheckFailureResponse(p.getId(), p.getName(), p.getEmail(), p.getEnteredAt(), consent.getConsentedAt(),
                    failed, noticed.contains(p.getId())));
        }
        return out;
    }

    /** 점검 JSON ({ pc: { items }, phone: { items } | null }) 에서 status 가 fail 인 항목. 모양이 달라도 있는 것만 읽는다 */
    private static List<EnvCheckFailureResponse.Item> failedItems(JsonNode report) {
        List<EnvCheckFailureResponse.Item> failed = new ArrayList<>();
        for (String channel : List.of("pc", "phone")) {
            JsonNode items = report.path(channel).path("items");
            if (!items.isArray()) continue;
            for (JsonNode item : items) {
                String id = item.path("id").asText("");
                if (id.isEmpty() || !"fail".equals(item.path("status").asText(""))) continue;
                failed.add(new EnvCheckFailureResponse.Item(id, item.path("label").asText(id), item.path("detail").asText("")));
            }
        }
        return failed;
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
