package com.pair.domain.exam.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.exam.dto.VisionEventRequest;
import com.pair.domain.exam.dto.VisionEventResponse;
import com.pair.domain.exam.service.VisionEventService;
import com.pair.domain.exam.service.VisionSignatureService;
import com.pair.global.config.VisionProperties;
import com.pair.global.error.CodedException;

/**
 * 추론 서버가 부르는 내부 API (계약 §3-4). 응시자·콘솔 API 와 같은 로그인 인증을 쓰지 않고 요청 본문 HMAC 서명만 인정한다.
 * 서버 추론 스위치가 꺼져 있으면 404 로 닫혀 있다. 본문은 서명을 맞추려고 원본 바이트로 받아 직접 읽는다.
 */
@RestController
public class VisionInternalController {

    /** 요청 본문 상한 (넘으면 413 TOO_LARGE) */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final VisionProperties props;
    private final VisionSignatureService signatures;
    private final VisionEventService eventService;
    private final ObjectMapper json;

    public VisionInternalController(VisionProperties props, VisionSignatureService signatures, VisionEventService eventService, ObjectMapper json) {
        this.props = props;
        this.signatures = signatures;
        this.eventService = eventService;
        this.json = json;
    }

    @PostMapping("/internal/vision/events")
    public ResponseEntity<VisionEventResponse> event(
            @RequestHeader(value = "X-Vision-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-Vision-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Vision-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        if (!props.enabled()) throw new CodedException(HttpStatus.NOT_FOUND, "VISION_DISABLED", "서버 추론 감독을 쓰지 않습니다.");
        if (body.length > MAX_BODY_BYTES) throw new CodedException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "요청 본문이 너무 큽니다.");
        switch (signatures.verify(keyId, timestamp, signature, body)) {
            case MISSING -> throw new CodedException(HttpStatus.UNAUTHORIZED, "MISSING_SIGNATURE", "서명 헤더가 없습니다.");
            case UNKNOWN_KEY_ID -> throw new CodedException(HttpStatus.FORBIDDEN, "UNKNOWN_KEY_ID", "모르는 키 번호입니다.");
            case STALE_TIMESTAMP -> throw new CodedException(HttpStatus.FORBIDDEN, "STALE_TIMESTAMP", "타임스탬프가 허용 범위 밖입니다.");
            case BAD_SIGNATURE -> throw new CodedException(HttpStatus.FORBIDDEN, "BAD_SIGNATURE", "서명이 맞지 않습니다.");
            case OK -> { }
        }
        VisionEventRequest req;
        try {
            req = json.readValue(body, VisionEventRequest.class);
        } catch (java.io.IOException e) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", "요청 본문을 읽지 못했습니다.");
        }
        VisionEventService.Outcome out = eventService.receive(req);
        return ResponseEntity.status(out.created() ? HttpStatus.CREATED : HttpStatus.OK).body(out.body());
    }
}
