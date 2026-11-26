package com.pair.domain.exam.controller;

import com.pair.domain.exam.dto.ClipUploadRequest;
import com.pair.domain.exam.dto.EnvProgressRequest;
import com.pair.domain.exam.dto.NoticeResponse;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.exam.service.ExamEntryService;
import com.pair.domain.exam.service.ProctorService;
import com.pair.domain.exam.dto.ConsentRequest;
import com.pair.domain.exam.dto.EntryInfoResponse;
import com.pair.domain.exam.dto.ProctorEventRequest;
import com.pair.domain.exam.dto.ProctorEventResponse;
import com.pair.domain.exam.dto.ServerTimeResponse;

import jakarta.validation.Valid;

/**
 * 응시자 API. 로그인 대신 초대 토큰(입장 링크)으로 본인을 확인한다.
 * 토큰은 경로에 들어가므로 서버 접근 로그에 토큰이 남지 않게 운영 로그 설정에서 가려야 한다.
 */
@RestController
public class ExamEntryController {

    private final ExamEntryService entry;
    private final ProctorService proctor;
    private final com.pair.domain.exam.service.EnvCheckService envCheck;
    private final com.pair.domain.exam.service.NoticeService notices;
    private final Clock clock;
    private final com.pair.domain.exam.service.EvidenceClipService clips;
    private final com.pair.domain.exam.service.EnvCheckPhotoService envPhotos;

    public ExamEntryController(ExamEntryService entry, ProctorService proctor, Clock clock,
            com.pair.domain.exam.service.EnvCheckService envCheck, com.pair.domain.exam.service.NoticeService notices,
            com.pair.domain.exam.service.EvidenceClipService clips, com.pair.domain.exam.service.EnvCheckPhotoService envPhotos) {
        this.envPhotos = envPhotos;
        this.clips = clips;
        this.envCheck = envCheck;
        this.notices = notices;
        this.entry = entry;
        this.proctor = proctor;
        this.clock = clock;
    }

    /** 서버 시각. 프론트는 (서버 시각 - 내 시각)을 보정값으로 써서 남은 시간을 그린다 */
    @GetMapping("/api/time")
    public ServerTimeResponse time() {
        return new ServerTimeResponse(clock.instant());
    }

    @GetMapping("/api/entry/{token}")
    public EntryInfoResponse info(@PathVariable String token) {
        return entry.info(token);
    }

    @PostMapping("/api/entry/{token}/consent")
    public EntryInfoResponse consent(@PathVariable String token, @Valid @RequestBody ConsentRequest req) {
        return entry.consent(token, req);
    }

    @PostMapping("/api/entry/{token}/enter")
    public EntryInfoResponse enter(@PathVariable String token) {
        return entry.enter(token);
    }

    /** 시험 화면의 연결 신호 (10초마다). 응답이 expired=true 면 링크가 만료된 것 */
    @PostMapping("/api/entry/{token}/heartbeat")
    public ExamEntryService.Heartbeat heartbeat(@PathVariable String token) {
        return entry.heartbeat(token);
    }

    @PostMapping("/api/entry/{token}/start")
    public EntryInfoResponse start(@PathVariable String token) {
        return entry.start(token);
    }

    /** 과제 하나 제출 */
    @PostMapping("/api/entry/{token}/tasks/{taskId}/submit")
    public EntryInfoResponse submitTask(@PathVariable String token, @PathVariable String taskId) {
        return entry.submitTask(token, taskId);
    }

    /** 남은 과제 모두 제출 (시험 끝내기) */
    @PostMapping("/api/entry/{token}/submit")
    public EntryInfoResponse submit(@PathVariable String token) {
        return entry.submit(token);
    }

    /** 환경 점검 진행 상태 (콘솔이 실시간으로 본다). 입장 대기 중 점검 화면이 올린다 */
    @PostMapping("/api/entry/{token}/env-progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void envProgress(@PathVariable String token, @Valid @RequestBody EnvProgressRequest req) {
        envCheck.saveProgress(token, req.result());
    }

    /** 환경 점검 사진(귀 왼쪽·오른쪽, 손목) 올리기. 단계마다 한 장이고 다시 올리면 덮어쓴다. 감독관이 사진으로 이어폰·스마트워치를 확인한다 */
    @PostMapping("/api/entry/{token}/env-check/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public com.pair.domain.exam.dto.EnvCheckPhotoResponse envCheckPhoto(@PathVariable String token,
            @Valid @RequestBody com.pair.domain.exam.dto.EnvCheckPhotoRequest req) {
        return envPhotos.upload(token, req);
    }

    /** 콘솔이 보낸 안내 메시지 (읽음 여부 포함). 입장 대기 화면과 시험 화면이 주기적으로 가져간다 */
    @GetMapping("/api/entry/{token}/notices")
    public java.util.List<NoticeResponse> notices(@PathVariable String token) {
        return notices.forEntry(token);
    }

    @PostMapping("/api/entry/{token}/notices/{noticeId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readNotice(@PathVariable String token, @PathVariable Long noticeId) {
        notices.markRead(token, noticeId);
    }

    @PostMapping("/api/entry/{token}/proctor-events")
    @ResponseStatus(HttpStatus.CREATED)
    public ProctorEventResponse proctorEvent(@PathVariable String token, @Valid @RequestBody ProctorEventRequest req) {
        return proctor.record(token, req);
    }

    /** 이벤트(clientId)의 증거 구간 사진 올리기. 응시자 PC 가 링 버퍼에서 이벤트 앞 8초·뒤 4초를 골라 보낸다 */
    @PostMapping("/api/entry/{token}/proctor-events/{clientId}/clip")
    @ResponseStatus(HttpStatus.CREATED)
    public void proctorClip(@PathVariable String token, @PathVariable String clientId, @Valid @RequestBody ClipUploadRequest req) {
        clips.upload(token, clientId, req);
    }
}
