package com.pair.domain.taskgen.controller;

import com.pair.domain.taskgen.dto.AutoTopicDto;
import com.pair.domain.taskgen.dto.TaskGenAutoRequest;
import com.pair.domain.taskgen.dto.TaskGenAutoResponse;
import com.pair.domain.taskgen.dto.TaskGenPreviewRequest;
import com.pair.domain.taskgen.dto.TaskGenStartRequest;
import com.pair.domain.taskgen.dto.TaskGenStartResponse;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.service.TaskAutoService;
import com.pair.domain.taskgen.service.TaskGenJobService;
import com.pair.domain.taskgen.service.TaskSuggestionService;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.global.security.CurrentUser;
import com.pair.global.error.ForbiddenException;

/** AI 과제 생성 API. 모두 관리자(ADMIN)만 쓴다 */
@RestController
@RequestMapping("/api/admin")
public class TaskGenController {

    private final TaskGenJobService jobs;
    private final GeneratedTaskRepository writer;
    private final TaskSuggestionService suggestions;
    private final TaskAutoService auto;

    public TaskGenController(TaskGenJobService jobs, GeneratedTaskRepository writer, TaskSuggestionService suggestions, TaskAutoService auto) {
        this.auto = auto;
        this.suggestions = suggestions;
        this.jobs = jobs;
        this.writer = writer;
    }

    /** 폼(+ mode, batchDeadlineHours)을 받아 생성 작업을 대기열에 넣는다. 202 {jobId}. 입력이 잘못되면 400. 동시 상한을 넘어도 거절하지 않고 대기한다 */
    @PostMapping("/task-gen")
    public ResponseEntity<TaskGenStartResponse> start(CurrentUser user, @RequestBody TaskGenStartRequest req) {
        requireAdmin(user);
        String jobId = jobs.start(req.toForm().validated(), user.userId(), req.mode(), req.batchDeadlineHours(), null);
        return ResponseEntity.accepted().body(new TaskGenStartResponse(jobId));
    }

    /** 자동 생성 주제 미리보기: count 개 [{situation, trap, trapType, competencies}] */
    @PostMapping("/task-gen/auto/preview")
    public List<AutoTopicDto> preview(CurrentUser user, @RequestBody TaskGenPreviewRequest req) {
        requireAdmin(user);
        return auto.preview(req, user.userId());
    }

    /** 자동 생성 시작: topics 가 있으면 그대로, 없으면 서버가 고른다. 주제마다 작업 하나. 202 {batchId, jobIds} */
    @PostMapping("/task-gen/auto")
    public ResponseEntity<TaskGenAutoResponse> startAuto(CurrentUser user, @RequestBody TaskGenAutoRequest req) {
        requireAdmin(user);
        TaskGenJobService.Batch b = auto.start(req, user.userId());
        return ResponseEntity.accepted().body(new TaskGenAutoResponse(b.batchId(), b.jobIds()));
    }

    /** 작업 목록: ?batchId= 이면 그 묶음, 아니면(?active=true) 대기·실행 중과 최근 24시간 안에 끝난 것 */
    @GetMapping("/task-gen/jobs")
    public List<TaskGenJobService.JobStatus> list(CurrentUser user, @RequestParam(required = false) String batchId,
            @RequestParam(defaultValue = "true") boolean active) {
        requireAdmin(user);
        if (batchId != null && !batchId.isBlank()) return jobs.batch(batchId);
        if (!active) throw new IllegalArgumentException("active=true 또는 batchId 를 보내세요.");
        return jobs.active();
    }

    /** 대기(QUEUED) 작업만 취소. 시작했거나 끝났으면 409 */
    @PostMapping("/task-gen/{jobId}/cancel")
    public TaskGenJobService.JobStatus cancel(CurrentUser user, @PathVariable String jobId) {
        requireAdmin(user);
        return jobs.cancel(jobId);
    }

    /** 주제·함정 후보 3개를 AI 가 추천한다(동기 호출). 400 입력 오류, 429 분당 한도, 503 AI 실패 */
    @PostMapping("/task-gen/suggestions")
    public TaskSuggestionService.Result suggest(CurrentUser user, @RequestBody TaskSuggestionService.Request req) {
        requireAdmin(user);
        return suggestions.suggest(req, user.userId());
    }

    @GetMapping("/task-gen/{jobId}")
    public TaskGenJobService.JobStatus status(CurrentUser user, @PathVariable String jobId) {
        requireAdmin(user);
        return jobs.status(jobId);
    }

    /** 과제 콘솔이 보여 줄 확인 결과(_gen/report.json). 없으면 404 */
    @GetMapping(value = "/tasks/{code}/gen-report", produces = MediaType.APPLICATION_JSON_VALUE)
    public String genReport(CurrentUser user, @PathVariable String code) {
        requireAdmin(user);
        return writer.report(code);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new ForbiddenException("AI 과제 생성은 관리자만 할 수 있습니다.");
    }
}
