package com.pair.domain.taskgen.controller;

import com.pair.domain.taskgen.dto.AutoTopicDto;
import com.pair.domain.taskgen.dto.TaskGenAutoRequest;
import com.pair.domain.taskgen.dto.TaskGenAutoResponse;
import com.pair.domain.taskgen.dto.TaskGenContentResponse;
import com.pair.domain.taskgen.dto.TaskGenCostEstimateResponse;
import com.pair.domain.taskgen.dto.TaskGenPreviewRequest;
import com.pair.domain.taskgen.dto.TaskGenStartRequest;
import com.pair.domain.taskgen.dto.TaskGenStartResponse;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.service.TaskAutoService;
import com.pair.domain.taskgen.service.TaskGenContentService;
import com.pair.domain.taskgen.service.TaskGenCostService;
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
    private final TaskGenContentService content;
    private final TaskGenCostService cost;

    public TaskGenController(TaskGenJobService jobs, GeneratedTaskRepository writer, TaskSuggestionService suggestions, TaskAutoService auto, TaskGenContentService content,
            TaskGenCostService cost) {
        this.cost = cost;
        this.content = content;
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

    /** 만들기 전 예상 비용: ?track=DEV|BIZ&minutes=&mode=NOW|BATCH&count= (최근 같은 조건 작업의 실제 비용 중앙값, 없으면 상한에서 어림) */
    @GetMapping("/task-gen/cost-estimate")
    public TaskGenCostEstimateResponse costEstimate(CurrentUser user, @RequestParam(required = false) String track,
            @RequestParam(required = false) Integer minutes, @RequestParam(required = false) String mode, @RequestParam(required = false) Integer count) {
        requireAdmin(user);
        return cost.estimate(track, minutes, mode, count);
    }

    /** 자동 생성 시작: topics 가 있으면 그대로, 없으면 서버가 고른다. 주제마다 작업 하나. 202 {batchId, jobIds} */
    @PostMapping("/task-gen/auto")
    public ResponseEntity<TaskGenAutoResponse> startAuto(CurrentUser user, @RequestBody TaskGenAutoRequest req) {
        requireAdmin(user);
        TaskGenJobService.Batch b = auto.start(req, user.userId());
        return ResponseEntity.accepted().body(new TaskGenAutoResponse(b.batchId(), b.jobIds()));
    }

    /** 작업 목록: ?batchId= 이면 그 묶음, 아니면(?active=true) 대기·실행 중과 최근 7일 안에 끝났는데 아직 확인하지 않은 것 */
    @GetMapping("/task-gen/jobs")
    public List<TaskGenJobService.JobStatus> list(CurrentUser user, @RequestParam(required = false) String batchId,
            @RequestParam(defaultValue = "true") boolean active) {
        requireAdmin(user);
        if (batchId != null && !batchId.isBlank()) return jobs.batch(batchId);
        if (!active) throw new IllegalArgumentException("active=true 또는 batchId 를 보내세요.");
        return jobs.active();
    }

    /** 끝난 작업 확인(목록에서 빠진다, 멱등). 아직 안 끝났으면 409, 없으면 404 */
    @PostMapping("/task-gen/{jobId}/seen")
    public TaskGenJobService.JobStatus seen(CurrentUser user, @PathVariable String jobId) {
        requireAdmin(user);
        return jobs.markSeen(jobId);
    }

    /** 끝난 작업 모두 확인: {seen: 바꾼 개수} */
    @PostMapping("/task-gen/seen-all")
    public java.util.Map<String, Integer> seenAll(CurrentUser user) {
        requireAdmin(user);
        return java.util.Map.of("seen", jobs.markAllSeen());
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

    /** 만드는 중인 과제의 중간 결과 요약(설계도·글·코드 파일 목록 등). 없는 작업이면 404 */
    @GetMapping("/task-gen/{jobId}/content")
    public TaskGenContentResponse content(CurrentUser user, @PathVariable String jobId) {
        requireAdmin(user);
        return content.content(jobId);
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
