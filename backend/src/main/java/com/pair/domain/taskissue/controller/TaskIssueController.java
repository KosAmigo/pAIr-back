package com.pair.domain.taskissue.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.taskissue.dto.TaskIssueRequest;
import com.pair.domain.taskissue.dto.TaskIssueResponse;
import com.pair.domain.taskissue.service.TaskIssueService;
import com.pair.global.security.CurrentUser;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/task-issues")
public class TaskIssueController {
    private final TaskIssueService issues;

    public TaskIssueController(TaskIssueService issues) {
        this.issues = issues;
    }

    @PostMapping
    public TaskIssueResponse submit(CurrentUser user, @Valid @RequestBody TaskIssueRequest request) {
        return issues.submit(user, request);
    }
}
