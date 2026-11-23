package com.pair.domain.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import com.pair.domain.exam.entity.ProctorEvent;

public record ProctorEventResponse(Long id, Long participantId, ProctorEvent.Type type, ProctorEvent.Source source, Instant startedAt,
        int durationMs, BigDecimal confidence, String detail, String snapshot, int snapshotBytes,
        ProctorEvent.Review review, String reviewNote, Long reviewedBy, Instant reviewedAt,
        /** 사진이 없는 카메라 이벤트의 사유: LIMIT_EXCEEDED(서버 사진 상한 초과) / UPLOAD_FAILED(업로드 실패·사진 없이 도착). 사진이 있거나 삭제됐거나 브라우저 이벤트면 null */
        String snapshotMissing) {

    public static ProctorEventResponse from(ProctorEvent e) {
        String snap = e.getSnapshotKey() == null ? null : e.getSnapshotDeletedAt() != null ? "deleted" : "ok";
        String missing = null;
        if (e.getSnapshotKey() == null && e.getSource() != ProctorEvent.Source.BROWSER) {
            missing = e.getSnapshotSkipReason() != null ? e.getSnapshotSkipReason().name() : "UPLOAD_FAILED";
        }
        return new ProctorEventResponse(e.getId(), e.getParticipantId(), e.getType(), e.getSource(), e.getStartedAt(), e.getDurationMs(),
                e.getConfidence(), e.getDetail(), snap, e.getSnapshotBytes(), e.getReview(), e.getReviewNote(),
                e.getReviewedBy(), e.getReviewedAt(), missing);
    }
}
