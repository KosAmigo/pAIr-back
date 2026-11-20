package com.pair.exam.web;

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

import com.pair.exam.service.ExamSessionService;
import com.pair.exam.service.ExamWorkbooks;
import com.pair.exam.service.ProctorService;
import com.pair.exam.service.SessionResultService;
import com.pair.exam.web.ExamDtos.AddParticipantsRequest;
import com.pair.exam.web.ExamDtos.AddParticipantsResult;
import com.pair.exam.web.ExamDtos.ProctorEventView;
import com.pair.exam.web.ExamDtos.ReviewRequest;
import com.pair.exam.web.ExamDtos.SessionDetail;
import com.pair.exam.web.ExamDtos.SessionRequest;
import com.pair.exam.web.ExamDtos.SessionSummary;
import com.pair.web.auth.CurrentUser;

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
    private final ConsoleAccess access;

    public ExamConsoleController(ExamSessionService sessions, SessionResultService results, ProctorService proctor,
            ConsoleAccess access) {
        this.sessions = sessions;
        this.results = results;
        this.proctor = proctor;
        this.access = access;
    }

    /** 내 회사 회차. 관리자는 companyId 를 넣으면 그 회사, 안 넣으면 전체 */
    @GetMapping
    public List<SessionSummary> list(CurrentUser user, @RequestParam(required = false) Long companyId) {
        access.requireConsole(user);
        if (user.isAdmin()) return companyId == null ? sessions.listAll() : sessions.list(companyId);
        return sessions.list(user.companyId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionSummary create(CurrentUser user, @Valid @RequestBody SessionRequest req) {
        access.requireManager(user);
        return sessions.create(req.withCompany(access.companyFor(user, req.companyId())), user.userId());
    }

    @GetMapping("/{sessionId}")
    public SessionDetail detail(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return sessions.detail(sessionId);
    }

    @PutMapping("/{sessionId}")
    public SessionSummary update(CurrentUser user, @PathVariable Long sessionId, @Valid @RequestBody SessionRequest req) {
        access.requireManager(user);
        access.requireSession(user, sessionId);
        return sessions.update(sessionId, req.withCompany(access.companyFor(user, req.companyId())));
    }

    /** 시작 전 취소. 지우지 않고 canceled_at 을 남기며 대상자에게 안내 메일을 보낸다 */
    @PostMapping("/{sessionId}/cancel")
    public SessionSummary cancel(CurrentUser user, @PathVariable Long sessionId) {
        manage(user, sessionId);
        return sessions.cancel(sessionId);
    }

    @PostMapping("/{sessionId}/participants")
    public AddParticipantsResult addParticipants(CurrentUser user, @PathVariable Long sessionId,
            @Valid @RequestBody AddParticipantsRequest req) {
        manage(user, sessionId);
        return sessions.addParticipants(sessionId, req.participants());
    }

    /** 대상자 CSV 업로드. dryRun=true 면 저장하지 않고 미리 보기(들어갈 인원, 거절 사유)만 돌려준다 */
    @PostMapping(value = "/{sessionId}/participants/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddParticipantsResult importCsv(CurrentUser user, @PathVariable Long sessionId, @RequestParam("file") MultipartFile file,
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

    /** 회차 전체 감독 이벤트 (시간순) */
    @GetMapping("/{sessionId}/events")
    public List<ProctorEventView> sessionEvents(CurrentUser user, @PathVariable Long sessionId) {
        access.requireSession(user, sessionId);
        return proctor.eventsOfSession(sessionId);
    }

    @GetMapping("/{sessionId}/participants/{participantId}/events")
    public List<ProctorEventView> events(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long participantId) {
        access.requireSession(user, sessionId);
        return proctor.eventsOf(sessionId, participantId);
    }

    /** 담당자 판정 (OK 문제 없음 / SUSPECT 부정 의심). 판정자는 로그인한 사람. 자동 탈락은 없다 */
    @PutMapping("/{sessionId}/events/{eventId}/review")
    public ProctorEventView review(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId,
            @Valid @RequestBody ReviewRequest req) {
        access.requireSession(user, sessionId);
        return proctor.review(sessionId, eventId, req.review(), req.note(), user.userId());
    }

    @GetMapping(value = "/{sessionId}/events/{eventId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(CurrentUser user, @PathVariable Long sessionId, @PathVariable Long eventId) {
        access.requireSession(user, sessionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proctor.snapshot(sessionId, eventId));
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
