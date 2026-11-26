package com.pair.domain.exam.controller;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.exam.dto.StreamListResponse;
import com.pair.domain.exam.service.ConsoleAccessService;
import com.pair.domain.exam.service.VisionStreamViewService;
import com.pair.global.security.CurrentUser;

/**
 * 감독 중단 구간의 상시 저장 사진 열람 (계약 §6-5). 감독관·매니저·관리자가 자기 회사 회차만 열 수 있고,
 * eventId 는 그 대상자의 감독 중단(SUPERVISION_PAUSED) 이벤트여야 하며 그 이벤트의 시각 범위 안만 열린다.
 */
@RestController
@RequestMapping("/api/console/sessions")
public class VisionConsoleController {

    private final VisionStreamViewService streams;
    private final ConsoleAccessService access;

    public VisionConsoleController(VisionStreamViewService streams, ConsoleAccessService access) {
        this.streams = streams;
        this.access = access;
    }

    @GetMapping("/{sessionId}/participants/{participantId}/stream")
    public StreamListResponse list(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId,
            @RequestParam Long eventId, @RequestParam(defaultValue = "webcam") String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) Integer limit) {
        access.requireSession(user, sessionId);
        return streams.list(sessionId, participantId, eventId, channel, from, limit, user.userId());
    }

    @GetMapping(value = "/{sessionId}/participants/{participantId}/stream/frame", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> frame(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId,
            @RequestParam Long eventId, @RequestParam(defaultValue = "webcam") String channel,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant t) {
        access.requireSession(user, sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(streams.frame(sessionId, participantId, eventId, channel, t, user.userId()));
    }
}
