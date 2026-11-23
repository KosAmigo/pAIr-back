package com.pair.domain.exam.dto;

import java.time.Instant;

/** 안내 메시지. readAt 이 비어 있으면 응시자가 아직 확인하지 않았다 */
public record NoticeResponse(Long id, String message, Instant sentAt, Instant readAt) {

    public static NoticeResponse from(com.pair.domain.exam.entity.ProctorNotice n) {
        return new NoticeResponse(n.getId(), n.getMessage(), n.getSentAt(), n.getReadAt());
    }
}
