package com.pair.domain.exam.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.exam.dto.VisionAdmissionDto;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ProctorConsentRepository;
import com.pair.global.config.VisionProperties;
import com.pair.global.security.InviteTokenService;

/**
 * 서버 추론 감독 적용 조건과 입장 판정.
 * 적용 조건(계약 §3-0): 백엔드 스위치(pair.vision.enabled) · 회차 플래그(server_vision) · 감독 사용(proctoring) 이 모두 참일 때만 서버 추론 모드다.
 * 하나라도 거짓이면 /ws/vision 은 닫혀 있고 기존 PC 분석 동작이 그대로다.
 */
@Service
public class VisionSessionService {

    /** 서버 추론 회차가 요구하는 동의 문구 버전 */
    public static final String CONSENT_VERSION = "v3";
    /** 시험이 끝난 뒤에도 늦게 도착하는 이벤트를 받는 시간(ProctorService)과 같이 맞춘다 */
    private static final Duration LATE_GRACE = Duration.ofMinutes(30);

    private final ExamParticipantRepository participants;
    private final ProctorConsentRepository consents;
    private final AttemptRepository attempts;
    private final VisionProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public VisionSessionService(ExamParticipantRepository participants, ProctorConsentRepository consents, AttemptRepository attempts,
            VisionProperties props, Clock clock) {
        this.participants = participants;
        this.consents = consents;
        this.attempts = attempts;
        this.props = props;
        this.clock = clock;
    }

    /** 이 회차가 지금 서버 추론 감독을 쓰는지 (세 조건이 모두 참) */
    public boolean isActive(ExamSession s) {
        return props.enabled() && s.isProctoring() && s.isServerVision();
    }

    /**
     * 응시자 PC 의 입장 토큰을 확인한다: 토큰·회차 상태(취소·종료 30분 뒤) · 적용 조건 · 동의 v3.
     * 통과하면 추론 서버에 알릴 불투명 키(visionKey)를 (없으면 만들어) 돌려준다. 토큰은 저장·로그에 남기지 않는다.
     */
    @Transactional
    public VisionAdmissionDto admit(String invite) {
        if (!InviteTokenService.looksValid(invite)) return VisionAdmissionDto.denied(VisionAdmissionDto.Result.UNAUTHORIZED);
        ExamParticipant p = participants.findByInviteTokenHash(InviteTokenService.hash(invite)).orElse(null);
        if (p == null) return VisionAdmissionDto.denied(VisionAdmissionDto.Result.UNAUTHORIZED);
        ExamSession s = p.getSession();
        Instant now = clock.instant();
        if (s.getCanceledAt() != null || now.isAfter(s.getEndsAt().plus(LATE_GRACE))) {
            return VisionAdmissionDto.denied(VisionAdmissionDto.Result.UNAUTHORIZED);
        }
        if (!isActive(s)) return VisionAdmissionDto.denied(VisionAdmissionDto.Result.DISABLED);
        boolean consented = consents.findById(p.getId()).map(c -> CONSENT_VERSION.equals(c.getConsentVersion())).orElse(false);
        if (!consented) return VisionAdmissionDto.denied(VisionAdmissionDto.Result.CONSENT_REQUIRED);
        if (p.getVisionKey() == null) {
            p.assignVisionKey(newKey());
            participants.flush();
        }
        return new VisionAdmissionDto(VisionAdmissionDto.Result.OK, s.getId(), p.getId(), p.getVisionKey(),
                attempts.existsByParticipantId(p.getId()), s.getEndsAt());
    }

    /** 대상자가 시험을 시작했는지 (check → exam 모드 전환 판단) */
    @Transactional(readOnly = true)
    public boolean examStarted(Long participantId) {
        return attempts.existsByParticipantId(participantId);
    }

    /** 22자 base64url (무작위 16바이트) */
    String newKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
