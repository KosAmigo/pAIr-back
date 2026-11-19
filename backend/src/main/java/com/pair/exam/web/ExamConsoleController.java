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
import com.pair.exam.service.ProctorService;
import com.pair.exam.service.SessionResultService;
import com.pair.exam.web.ExamDtos.AddParticipantsRequest;
import com.pair.exam.web.ExamDtos.AddParticipantsResult;
import com.pair.exam.web.ExamDtos.ProctorEventView;
import com.pair.exam.web.ExamDtos.ReviewRequest;
import com.pair.exam.web.ExamDtos.SessionDetail;
import com.pair.exam.web.ExamDtos.SessionRequest;
import com.pair.exam.web.ExamDtos.SessionSummary;

import jakarta.validation.Valid;

/**
 * 기업 콘솔 API: 회차 생성·수정·취소, 대상자(CSV), 진행 현황, 결과, 감독 이벤트·사진·판정.
 * TODO(인증): 기업 로그인이 백엔드에 생기면 companyId 를 요청 값이 아니라 로그인 정보에서 꺼내고,
 *            회차의 회사와 로그인 회사가 같은지 확인해야 한다. 지금은 프로토타입이라 열려 있다.
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

    public ExamConsoleController(ExamSessionService sessions, SessionResultService results, ProctorService proctor) {
        this.sessions = sessions;
        this.results = results;
        this.proctor = proctor;
    }

    @GetMapping
    public List<SessionSummary> list(@RequestParam Long companyId) {
        return sessions.list(companyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionSummary create(@Valid @RequestBody SessionRequest req) {
        return sessions.create(req);
    }

    @GetMapping("/{sessionId}")
    public SessionDetail detail(@PathVariable Long sessionId) {
        return sessions.detail(sessionId);
    }

    @PutMapping("/{sessionId}")
    public SessionSummary update(@PathVariable Long sessionId, @Valid @RequestBody SessionRequest req) {
        return sessions.update(sessionId, req);
    }

    /** 시작 전 취소. 지우지 않고 canceled_at 을 남기며 대상자에게 안내 메일을 보낸다 */
    @PostMapping("/{sessionId}/cancel")
    public SessionSummary cancel(@PathVariable Long sessionId) {
        return sessions.cancel(sessionId);
    }

    @PostMapping("/{sessionId}/participants")
    public AddParticipantsResult addParticipants(@PathVariable Long sessionId, @Valid @RequestBody AddParticipantsRequest req) {
        return sessions.addParticipants(sessionId, req.participants());
    }

    /** 대상자 CSV 업로드. dryRun=true 면 저장하지 않고 미리 보기(들어갈 인원, 거절 사유)만 돌려준다 */
    @PostMapping(value = "/{sessionId}/participants/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddParticipantsResult importCsv(@PathVariable Long sessionId, @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("파일이 비어 있습니다.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".csv")) throw new IllegalArgumentException("CSV(.csv) 파일만 올릴 수 있습니다. 양식을 내려받아 써 주세요.");
        try {
            return sessions.importCsv(sessionId, file.getBytes(), dryRun);
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽지 못했습니다.");
        }
    }

    /** 대상자 CSV 양식 내려받기 */
    @GetMapping("/participants-template.csv")
    public ResponseEntity<byte[]> participantsTemplate() {
        ContentDisposition cd = ContentDisposition.attachment().filename("participants-template.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(PARTICIPANTS_TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{sessionId}/participants/{participantId}/invite")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendInvite(@PathVariable Long sessionId, @PathVariable Long participantId) {
        sessions.resendInvite(sessionId, participantId);
    }

    @GetMapping("/{sessionId}/participants/{participantId}/events")
    public List<ProctorEventView> events(@PathVariable Long sessionId, @PathVariable Long participantId) {
        return proctor.eventsOf(sessionId, participantId);
    }

    /** 담당자 판정 (OK 문제 없음 / SUSPECT 부정 의심). 자동 탈락은 없다 */
    @PutMapping("/{sessionId}/events/{eventId}/review")
    public ProctorEventView review(@PathVariable Long sessionId, @PathVariable Long eventId,
            @Valid @RequestBody ReviewRequest req) {
        return proctor.review(sessionId, eventId, req.review(), req.note(), req.reviewerId());
    }

    @GetMapping(value = "/{sessionId}/events/{eventId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable Long sessionId, @PathVariable Long eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proctor.snapshot(sessionId, eventId));
    }

    @GetMapping("/{sessionId}/results")
    public SessionResultService.Result results(@PathVariable Long sessionId) {
        return results.result(sessionId);
    }

    @GetMapping("/{sessionId}/results.csv")
    public ResponseEntity<byte[]> csv(@PathVariable Long sessionId) {
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("session-" + sessionId + "-results.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(results.csv(sessionId).getBytes(StandardCharsets.UTF_8));
    }
}
