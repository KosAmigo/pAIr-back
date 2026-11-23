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
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.entity.SnapshotRetentionExtension;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.repository.SnapshotRetentionExtensionRepository;
import com.pair.domain.exam.dto.ExamDtos.RetentionView;

/**
 * 감독 사진 보관 기간. 기본은 회차 종료 후 snapshot-retention-days(7일).
 * 삭제 예정 2일 전부터 감독관·매니저가 7일씩 최대 2번 늘릴 수 있고, 누가 언제 늘렸는지 이력을 남긴다.
 */
@Service
public class SnapshotRetentionService {

    public static final int EXTENSION_DAYS = 7;
    public static final int MAX_EXTENSIONS = 2;
    /** 삭제 예정 시각의 이 시간 전부터 연장 버튼이 열린다 */
    public static final Duration EXTEND_WINDOW = Duration.ofDays(2);

    private final ExamSessionRepository sessions;
    private final SnapshotRetentionExtensionRepository extensions;
    private final ExamProperties props;
    private final Clock clock;

    public SnapshotRetentionService(ExamSessionRepository sessions, SnapshotRetentionExtensionRepository extensions,
            ExamProperties props, Clock clock) {
        this.sessions = sessions;
        this.extensions = extensions;
        this.props = props;
        this.clock = clock;
    }

    /** 삭제 예정 시각 = 종료 + 기본 보관일 + 연장 횟수 x 7일 */
    public Instant expiryOf(Instant endsAt, long extensionsUsed) {
        return endsAt.plus(Duration.ofDays(props.snapshotRetentionDays() + extensionsUsed * EXTENSION_DAYS));
    }

    @Transactional(readOnly = true)
    public RetentionView view(Long sessionId) {
        ExamSession s = session(sessionId);
        return viewOf(s, extensions.findBySessionIdOrderBySeqAsc(sessionId));
    }

    @Transactional
    public RetentionView extend(Long sessionId, Long userId) {
        ExamSession s = session(sessionId);
        List<SnapshotRetentionExtension> history = extensions.findBySessionIdOrderBySeqAsc(sessionId);
        if (history.size() >= MAX_EXTENSIONS) throw new IllegalStateException("보관 연장은 최대 " + MAX_EXTENSIONS + "번까지입니다.");
        Instant now = clock.instant();
        Instant expiresAt = expiryOf(s.getEndsAt(), history.size());
        if (!now.isBefore(expiresAt)) throw new IllegalStateException("이미 삭제 예정 시각이 지나 연장할 수 없습니다.");
        if (now.isBefore(expiresAt.minus(EXTEND_WINDOW))) throw new IllegalStateException("삭제 예정 2일 전부터 연장할 수 있습니다.");
        try {
            extensions.saveAndFlush(new SnapshotRetentionExtension(sessionId, history.size() + 1, userId, now, EXTENSION_DAYS,
                    expiresAt, expiryOf(s.getEndsAt(), history.size() + 1L)));
        } catch (DataIntegrityViolationException raced) {
            throw new IllegalStateException("다른 담당자가 방금 연장했습니다. 새로고침해서 확인하세요.");
        }
        return viewOf(s, extensions.findBySessionIdOrderBySeqAsc(sessionId));
    }

    private RetentionView viewOf(ExamSession s, List<SnapshotRetentionExtension> history) {
        Instant now = clock.instant();
        Instant expiresAt = expiryOf(s.getEndsAt(), history.size());
        boolean canExtend = history.size() < MAX_EXTENSIONS && now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(EXTEND_WINDOW));
        return new RetentionView(expiresAt, history.size(), MAX_EXTENSIONS, EXTENSION_DAYS, canExtend,
                history.stream().map(h -> new RetentionView.Entry(h.getSeq(), h.getExtendedBy(), h.getExtendedAt(), h.getDays(),
                        h.getExpiresBefore(), h.getExpiresAfter())).toList());
    }

    private ExamSession session(Long id) {
        return sessions.findById(id).orElseThrow(() -> new NoSuchElementException("없는 회차입니다: " + id));
    }
}
