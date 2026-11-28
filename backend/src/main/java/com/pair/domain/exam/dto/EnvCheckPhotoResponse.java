package com.pair.domain.exam.dto;

import java.time.Instant;
import com.pair.domain.exam.entity.EnvCheckPhoto;

public record EnvCheckPhotoResponse(Long id, Long participantId, EnvCheckPhoto.Stage stage, int sizeBytes, Instant capturedAt, boolean deleted) {

    public static EnvCheckPhotoResponse from(EnvCheckPhoto f) {
        return new EnvCheckPhotoResponse(f.getId(), f.getParticipantId(), f.getStage(), f.getSizeBytes(), f.getCapturedAt(), f.getDeletedAt() != null);
    }
}
