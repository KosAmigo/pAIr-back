package com.pair.domain.exam.controller;

import com.pair.domain.exam.dto.ClipResponse;
import com.pair.domain.exam.dto.EnvCheckResponse;
import com.pair.domain.exam.dto.NoticeRequest;
import com.pair.domain.exam.dto.NoticeResponse;
import com.pair.domain.exam.dto.RetentionResponse;
import com.pair.domain.exam.service.ConsoleAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pair.domain.exam.service.AutoSubmitService;
import com.pair.domain.exam.service.ConnectionGrace;
import com.pair.domain.exam.service.EnvScanService;
import com.pair.domain.exam.service.ExamSessionService;
import com.pair.domain.exam.service.ExamWorkbooks;
import com.pair.domain.exam.service.ProctorService;
import com.pair.domain.exam.service.SessionResultService;
import com.pair.domain.exam.dto.AddParticipantsRequest;
import com.pair.domain.exam.dto.AddParticipantsResponse;
import com.pair.domain.exam.dto.EnvScanPhotoResponse;
import com.pair.domain.exam.dto.ParticipantResponse;
import com.pair.domain.exam.dto.ProctorEventResponse;
import com.pair.domain.exam.dto.ReviewRequest;
import com.pair.domain.exam.dto.SessionDetailResponse;
import com.pair.domain.exam.dto.ExamSessionRequest;
import com.pair.domain.exam.dto.SessionSummaryResponse;
import com.pair.global.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * 기업 콘솔 API: 회차 생성·수정·취소, 대상자(CSV), 진행 현황, 결과, 감독 이벤트·사진·판정.
 * 로그인 사용자(CurrentUser)의 회사 회차만 다룬다 (ConsoleAccess). 관리자는 모든 회사.
 * MANAGER 는 관리·결과까지, PROCTOR 는 보기·감독(이벤트·사진·판정)만.
 */
@RestController
@RequestMapping("/api/console/sessions")
@Validated
public class ExamConsoleController {

    /** 사이트가 주는 대상자 양식. 엑셀에서 한글이 깨지지 않게 UTF-8 BOM 을 붙인다 */
    static final String PARTICIPANTS_TEMPLATE = "\uFEFF이름,이메일\r\n홍길동,hong@example.com\r\n";

    private final ExamSessionService sessions;
    private final SessionResultService results;
    private final ProctorService proctor;
    private final AutoSubmitService autoSubmit;
    private final EnvScanService envScan;
    private final com.pair.domain.exam.service.EnvCheckService envCheck;
    private final com.pair.domain.exam.service.NoticeService notices;
    private final ConnectionGrace grace;
    private final ConsoleAccess access;
    private final com.pair.domain.exam.service.SnapshotRetentionService retention;
    private final com.pair.domain.exam.service.EvidenceClipService clips;

    public ExamConsoleController(ExamSessionService sessions, SessionResultService results, ProctorService proctor,
            AutoSubmitService autoSubmit, EnvScanService envScan, ConnectionGrace grace, ConsoleAccess access,
            com.pair.domain.exam.service.EnvCheckService envCheck, com.pair.domain.exam.service.NoticeService notices,
            com.pair.domain.exam.service.SnapshotRetentionService retention, com.pair.domain.exam.service.EvidenceClipService clips) {
        this.retention = retention;
        this.clips = clips;
        this.envCheck = envCheck;
        this.notices = notices;
        this.sessions = sessions;
        this.results = results;
        this.proctor = proctor;
        this.autoSubmit = autoSubmit;
        this.envScan = envScan;
        this.grace = grace;
        this.access = access;
    }

    /** 내 회사 회차. 관리자는 companyId 를 넣으면 그 회사, 안 넣으면 전체 */
    @GetMapping
    public List<SessionSummaryResponse> list(CurrentUser user, @RequestParam(required = false) Long companyId) {
        access.requireConsole(user);
        if (user.isAdmin()) return companyId == null ? sessions.listAll() : sessions.list(companyId);
        return sessions.list(user.companyId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionSummaryResponse create(CurrentUser user, @Valid @RequestBody ExamSessionRequest req) {
        access.requireManager(user);
        return sessions.create(req.withCompany(access.companyFor(user, req.companyId())), user.userId());
    }

    @GetMapping("/{sessionId}")
    public SessionDetailResponse detail(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return sessions.detail(sessionId);
    }

    @PutMapping("/{sessionId}")
    public SessionSummaryResponse update(CurrentUser user, @PathVariable Long sessionId, @Valid @RequestBody ExamSessionRequest req) {
        access.requireManager(user);
        access.requireSession(user, sessionId);
        return sessions.update(sessionId, req.withCompany(access.companyFor(user, req.companyId())));
    }

    /** 시작 전 취소. 지우지 않고 canceled_at 을 남기며 대상자에게 안내 메일을 보낸다 */
    @PostMapping("/{sessionId}/cancel")
    public SessionSummaryResponse cancel(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        return sessions.cancel(sessionId);
    }

    @PostMapping("/{sessionId}/participants")
    public AddParticipantsResponse addParticipants(CurrentUser user, @PathVariable Long sessionId,
            @Valid @RequestBody AddParticipantsRequest req) {
        manage(user, sessionId);
        return sessions.addParticipants(sessionId, req.participants());
    }

    /** 대상자 CSV 업로드. dryRun=true 면 저장하지 않고 미리 보기(들어갈 인원, 거절 사유)만 돌려준다 */
    @PostMapping(value = "/{sessionId}/participants/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddParticipantsResponse importCsv(CurrentUser user, @PathVariable Long sessionId, @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        manage(user, sessionId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("파일이 비어 있습니다.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".csv") && !name.endsWith(".xlsx")) throw new IllegalArgumentException("CSV(.csv) 또는 엑셀(.xlsx) 파일만 올릴 수 있습니다. 양식을 내려받아 써 주세요.");
        try {
            return sessions.importCsv(sessionId, file.getBytes(), dryRun);
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽지 못했습니다.");
        }
    }

    /** 대상자 CSV 양식 내려받기 (공개 양식이라 로그인 없이도 받는다) */
    @GetMapping("/participants-template.csv")
    public ResponseEntity<byte[]> participantsTemplate() {
        ContentDisposition cd = ContentDisposition.attachment().filename("participants-template.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(PARTICIPANTS_TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    /** 꾸민 엑셀 양식 (기본 양식). CSV 양식은 그대로 둔다 */
    @GetMapping("/participants-template.xlsx")
    public ResponseEntity<byte[]> participantsTemplateXlsx() {
        return xlsx("대상자_양식.xlsx", ExamWorkbooks.participantsTemplate());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{sessionId}/participants/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        manage(user, sessionId);
        sessions.removeParticipant(sessionId, participantId);
    }

    @PostMapping("/{sessionId}/participants/{participantId}/invite")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendInvite(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        manage(user, sessionId);
        sessions.resendInvite(sessionId, participantId);
    }

    /**
     * 연결 유예를 넘겨 만료된 응시자의 입장 링크를 되살린다 (매니저·감독관·관리자). 시험 시간은 연장하지 않는다.
     * 되살린 사람과 시각은 감독 이벤트(LINK_RESTORED)로 남는다.
     */
    @PostMapping("/{sessionId}/participants/{participantId}/restore-link")
    public ParticipantResponse restoreLink(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        return grace.restore(sessionId, participantId, user.userId());
    }

    /** 회차 전체 감독 이벤트 (시간순) */
    @GetMapping("/{sessionId}/events")
    public List<ProctorEventResponse> sessionEvents(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return proctor.eventsOfSession(sessionId);
    }

    @GetMapping("/{sessionId}/participants/{participantId}/events")
    public List<ProctorEventResponse> events(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        return proctor.eventsOf(sessionId, participantId);
    }

    /** 담당자 판정 (OK 문제 없음 / SUSPECT 부정 의심). 판정자는 로그인한 사람. 자동 탈락은 없다 */
    @PutMapping("/{sessionId}/events/{eventId}/review")
    public ProctorEventResponse review(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId,
            @Valid @RequestBody ReviewRequest req) {
        access.requireSession(user, sessionId);
        return proctor.review(sessionId, eventId, req.review(), req.note(), user.userId());
    }

    @GetMapping(value = "/{sessionId}/events/{eventId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId) {
        access.requireSession(user, sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proctor.snapshot(sessionId, eventId));
    }

    /** 이벤트의 증거 구간 (앞 8초·뒤 4초 사진 목록, 시간순). 완료된 회차에서도 볼 수 있다 */
    @GetMapping("/{sessionId}/events/{eventId}/clip")
    public ClipResponse clip(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId) {
        access.requireSession(user, sessionId);
        return clips.clipOf(sessionId, eventId);
    }

    @GetMapping(value = "/{sessionId}/events/{eventId}/clip/{frameId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> clipImage(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId,
            @PathVariable Long frameId) {
        access.requireSession(user, sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(clips.frameImage(sessionId, eventId, frameId));
    }

    /** 회차의 이벤트별 증거 사진 수 ({이벤트 번호: 장수}). 타임라인에서 증거 구간이 있는 이벤트를 표시한다 */
    @GetMapping("/{sessionId}/clips")
    public java.util.Map<Long, Long> clipCounts(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return clips.countsOfSession(sessionId);
    }

    /** 감독 사진 보관 현황 (삭제 예정 시각, 연장 가능 여부, 연장 이력). 감독관도 볼 수 있다 */
    @GetMapping("/{sessionId}/retention")
    public RetentionResponse retention(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return retention.view(sessionId);
    }

    /** 감독 사진 보관 연장: 삭제 예정 2일 전부터 7일씩 최대 2번. 감독관·매니저 모두 가능, 누가 했는지 이력에 남는다 */
    @PostMapping("/{sessionId}/retention/extend")
    public RetentionResponse extendRetention(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return retention.extend(sessionId, user.userId());
    }

    /** 종료된 회차에서 채점으로 넘어가지 못한 응시를 다시 넘긴다 (자동 제출이 포기한 건 복구) */
    @PostMapping("/{sessionId}/rescue")
    public AutoSubmitService.RescueResult rescue(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        return autoSubmit.rescue(sessionId);
    }

    /** 대상자의 환경 점검 사진 목록 (방 360도 방향별, 책상 위). 감독관도 볼 수 있다 */
    @GetMapping("/{sessionId}/participants/{participantId}/env-scan")
    public List<EnvScanPhotoResponse> envScanPhotos(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        return envScan.photosOf(sessionId, participantId);
    }

    /** 대상자의 환경 점검 결과 (진행 중이면 실시간, 아니면 저장된 결과). 아직 없으면 204. 감독관도 볼 수 있다 */
    @GetMapping("/{sessionId}/participants/{participantId}/env-check")
    public ResponseEntity<EnvCheckResponse> envCheck(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        EnvCheckResponse view = envCheck.view(sessionId, participantId);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    /** 응시자에게 안내 메시지 보내기 (시험 전·중). 감독관도 보낼 수 있다 */
    @PostMapping("/{sessionId}/participants/{participantId}/notices")
    @ResponseStatus(HttpStatus.CREATED)
    public NoticeResponse sendNotice(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId,
            @Valid @RequestBody NoticeRequest req) {
        access.requireSession(user, sessionId);
        return notices.send(sessionId, participantId, req.message(), user.userId());
    }

    /** 보낸 안내 메시지와 읽음 여부 */
    @GetMapping("/{sessionId}/participants/{participantId}/notices")
    public List<NoticeResponse> noticesOf(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        return notices.listOf(sessionId, participantId);
    }

    @GetMapping(value = "/{sessionId}/env-scan/{photoId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> envScanImage(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long photoId) {
        access.requireSession(user, sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envScan.image(sessionId, photoId));
    }

    @GetMapping("/{sessionId}/results")
    public SessionResultService.Result results(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        return results.result(sessionId);
    }

    @GetMapping("/{sessionId}/results.csv")
    public ResponseEntity<byte[]> csv(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("session-" + sessionId + "-results.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(results.csv(sessionId).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{sessionId}/results.xlsx")
    public ResponseEntity<byte[]> resultsXlsx(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        return xlsx("session-" + sessionId + "-results.xlsx", results.xlsx(sessionId));
    }

    private static ResponseEntity<byte[]> xlsx(String filename, byte[] body) {
        ContentDisposition cd = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    private void manage(CurrentUser user, Long sessionId) {
        access.requireManager(user);
        access.requireSession(user, sessionId);
    }
}
