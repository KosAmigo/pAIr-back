package com.pair.domain.taskgen.controller;

import com.pair.domain.taskgen.dto.TaskGenForm;
import com.pair.domain.taskgen.service.GeneratedTaskWriter;
import com.pair.domain.taskgen.service.TaskGenJobs;
import com.pair.domain.taskgen.service.TaskSuggestions;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.global.security.CurrentUser;
import com.pair.global.error.ForbiddenException;

/** AI 과제 생성 API. 모두 관리자(ADMIN)만 쓴다 */
@RestController
@RequestMapping("/api/admin")
public class TaskGenController {

    private final TaskGenJobs jobs;
    private final GeneratedTaskWriter writer;
    private final TaskSuggestions suggestions;

    public TaskGenController(TaskGenJobs jobs, GeneratedTaskWriter writer, TaskSuggestions suggestions) {
        this.suggestions = suggestions;
        this.jobs = jobs;
        this.writer = writer;
    }

    /** 폼을 받아 생성 작업을 시작한다. 202 {jobId}. 입력이 잘못되면 400, 동시 상한을 넘으면 429 */
    @PostMapping("/task-gen")
    public ResponseEntity<Map<String, String>> start(CurrentUser user, @RequestBody TaskGenForm form) {
        requireAdmin(user);
        String jobId = jobs.start(form.validated(), user.userId());
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    /** 주제·함정 후보 3개를 AI 가 추천한다(동기 호출). 400 입력 오류, 429 분당 한도, 503 AI 실패 */
    @PostMapping("/task-gen/suggestions")
    public TaskSuggestions.Result suggest(CurrentUser user, @RequestBody TaskSuggestions.Request req) {
        requireAdmin(user);
        return suggestions.suggest(req, user.userId());
    }

    @GetMapping("/task-gen/{jobId}")
    public TaskGenJobs.JobStatus status(CurrentUser user, @PathVariable String jobId) {
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
