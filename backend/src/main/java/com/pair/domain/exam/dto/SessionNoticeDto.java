package com.pair.domain.exam.dto;

import java.util.List;

/** 회차 변경·취소 안내 메일 대상. 커밋된 뒤에 보낸다. */
public record SessionNoticeDto(Long sessionId, Kind kind, List<Long> participantIds) {

    public enum Kind { CHANGED, CANCELED }
}
