package com.pair.domain.taskissue.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.taskissue.dto.AdminTaskIssueResponse;
import com.pair.domain.taskissue.dto.TaskIssuePageResponse;
import com.pair.domain.taskissue.dto.TaskIssueStatusRequest;
import com.pair.domain.taskissue.entity.TaskIssue;
import com.pair.domain.taskissue.service.TaskIssueService;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/task-issues")
public class AdminTaskIssueController {
    private final TaskIssueService issues;

    public AdminTaskIssueController(TaskIssueService issues) {
        this.issues = issues;
    }

    @GetMapping
    public TaskIssuePageResponse list(CurrentUser user,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) TaskIssue.Category category,
            @RequestParam(required = false) TaskIssue.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdmin(user);
        return issues.list(taskId, category, status, page, size);
    }

    @PatchMapping("/{issueId}/status")
    public AdminTaskIssueResponse changeStatus(CurrentUser user, @PathVariable long issueId,
            @Valid @RequestBody TaskIssueStatusRequest request) {
        requireAdmin(user);
        return issues.changeStatus(issueId, request.status());
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new ForbiddenException("문제 건의함은 관리자만 볼 수 있습니다.");
    }
}
