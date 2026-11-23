package com.pair.domain.exam.dto;

import java.time.Instant;
import java.util.List;
import com.pair.domain.exam.entity.EnvScanPhoto;

public record EnvScanPhotoResponse(Long id, Long participantId, EnvScanPhoto.Stage stage, Integer angle, List<String> objects,
        boolean phoneSuspect, int sizeBytes, Instant capturedAt, boolean deleted) {

    public static EnvScanPhotoResponse from(EnvScanPhoto f) {
        List<String> labels = f.getObjects() == null || f.getObjects().isBlank() ? List.of() : List.of(f.getObjects().split(","));
        return new EnvScanPhotoResponse(f.getId(), f.getParticipantId(), f.getStage(), f.getAngleDeg() == null ? null : f.getAngleDeg().intValue(),
                labels, f.isPhoneSuspect(), f.getSizeBytes(), f.getCapturedAt(), f.getDeletedAt() != null);
    }
}
