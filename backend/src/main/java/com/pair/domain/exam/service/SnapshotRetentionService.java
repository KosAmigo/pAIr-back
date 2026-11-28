package com.pair.domain.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.config.ExamProperties;
import com.pair.global.config.VisionProperties;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.SnapshotRetentionExtension;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.repository.SnapshotRetentionExtensionRepository;
import com.pair.domain.exam.dto.RetentionResponse;

/**
 * 감독 사진 보관 기간 (이벤트 대표 사진·이벤트 구간 사진).
 * 서버 추론이 꺼진 회차(기존 동작 그대로): 회차 종료 후 snapshot-retention-days(7일), 삭제 예정 2일 전부터 감독관·매니저가 7일씩 최대 2번 연장.
 * 서버 추론 회차: 종료 후 clip-retention-days(3일), 3일씩 최대 2번, 삭제 예정 extend-window-days(1일) 전부터 연장 (값은 pair.vision 설정).
 * 어느 쪽이든 누가 언제 늘렸는지 이력을 남긴다. 상시 저장분(웹캠·휴대폰 전체 사진)은 연장하지 않고 StreamRecorderService 가 지운다.
 */
@Service
public class SnapshotRetentionService {

    /** 서버 추론이 아닌 회차의 연장 규칙 (기존 동작) */
    public static final int EXTENSION_DAYS = 7;
    public static final int MAX_EXTENSIONS = 2;
    /** 삭제 예정 시각의 이 시간 전부터 연장 버튼이 열린다 */
    public static final Duration EXTEND_WINDOW = Duration.ofDays(2);

    private final ExamSessionRepository sessions;
    private final SnapshotRetentionExtensionRepository extensions;
    private final ExamProperties props;
    private final VisionProperties vision;
    private final Clock clock;

    public SnapshotRetentionService(ExamSessionRepository sessions, SnapshotRetentionExtensionRepository extensions,
            ExamProperties props, VisionProperties vision, Clock clock) {
        this.sessions = sessions;
        this.extensions = extensions;
        this.props = props;
        this.vision = vision;
        this.clock = clock;
    }

    /** 삭제 예정 시각 = 종료 + 기본 보관일 + 연장 횟수 x 연장 일수 (회차의 서버 추론 여부에 따라 값이 다르다) */
    public Instant expiryOf(ExamSession s, long extensionsUsed) {
        return s.getEndsAt().plus(Duration.ofDays(baseDays(s) + extensionsUsed * extensionDays(s)));
    }

    /** 삭제 배치가 후보 회차를 거를 때 쓰는 가장 짧은 기본 보관일 (이보다 일찍 끝난 회차만 살핀다) */
    public int shortestBaseDays() {
        return (int) Math.min(props.snapshotRetentionDays(), vision.clipRetentionDays());
    }

    @Transactional(readOnly = true)
    public RetentionResponse view(Long sessionId) {
        ExamSession s = session(sessionId);
        return viewOf(s, extensions.findBySessionIdOrderBySeqAsc(sessionId));
    }

    @Transactional
    public RetentionResponse extend(Long sessionId, Long userId) {
        ExamSession s = session(sessionId);
        List<SnapshotRetentionExtension> history = extensions.findBySessionIdOrderBySeqAsc(sessionId);
        if (history.size() >= maxExtensions(s)) throw new IllegalStateException("보관 연장은 최대 " + maxExtensions(s) + "번까지입니다.");
        Instant now = clock.instant();
        // 종료된 회차만 연장한다 (시작 전·진행 중·취소된 회차는 거절)
        if (s.getCanceledAt() != null || now.isBefore(s.getEndsAt())) throw new IllegalStateException("종료된 회차만 보관을 연장할 수 있습니다.");
        Instant expiresAt = expiryOf(s, history.size());
        if (!now.isBefore(expiresAt)) throw new IllegalStateException("이미 삭제 예정 시각이 지나 연장할 수 없습니다.");
        if (now.isBefore(expiresAt.minus(extendWindow(s)))) {
            throw new IllegalStateException("삭제 예정 " + extendWindow(s).toDays() + "일 전부터 연장할 수 있습니다.");
        }
        try {
            extensions.saveAndFlush(new SnapshotRetentionExtension(sessionId, history.size() + 1, userId, now, extensionDays(s),
                    expiresAt, expiryOf(s, history.size() + 1L)));
        } catch (DataIntegrityViolationException raced) {
            throw new IllegalStateException("다른 담당자가 방금 연장했습니다. 새로고침해서 확인하세요.");
        }
        return viewOf(s, extensions.findBySessionIdOrderBySeqAsc(sessionId));
    }

    private RetentionResponse viewOf(ExamSession s, List<SnapshotRetentionExtension> history) {
        Instant now = clock.instant();
        Instant expiresAt = expiryOf(s, history.size());
        boolean canExtend = history.size() < maxExtensions(s) && now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(extendWindow(s)));
        return new RetentionResponse(expiresAt, history.size(), maxExtensions(s), extensionDays(s), canExtend,
                history.stream().map(h -> new RetentionResponse.Entry(h.getSeq(), h.getExtendedBy(), h.getExtendedAt(), h.getDays(),
                        h.getExpiresBefore(), h.getExpiresAfter())).toList(),
                baseDays(s), (int) extendWindow(s).toDays());
    }

    private int baseDays(ExamSession s) {
        return s.isServerVision() ? vision.clipRetentionDays() : (int) props.snapshotRetentionDays();
    }

    private int extensionDays(ExamSession s) {
        return s.isServerVision() ? vision.extensionDays() : EXTENSION_DAYS;
    }

    private int maxExtensions(ExamSession s) {
        return s.isServerVision() ? vision.maxExtensions() : MAX_EXTENSIONS;
    }

    private Duration extendWindow(ExamSession s) {
        return s.isServerVision() ? Duration.ofDays(vision.extendWindowDays()) : EXTEND_WINDOW;
    }

    private ExamSession session(Long id) {
        return sessions.findById(id).orElseThrow(() -> new NoSuchElementException("없는 회차입니다: " + id));
    }
}
