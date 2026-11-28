package com.pair.domain.taskissue.service;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskissue.dto.AdminTaskIssueResponse;
import com.pair.domain.taskissue.dto.TaskIssuePageResponse;
import com.pair.domain.taskissue.dto.TaskIssueRequest;
import com.pair.domain.taskissue.dto.TaskIssueResponse;
import com.pair.domain.taskissue.entity.TaskIssue;
import com.pair.domain.taskissue.repository.TaskIssueRepository;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;

@Service
public class TaskIssueService {
    private final TaskIssueRepository issues;
    private final TaskRepository tasks;
    private final AuthAccountRepository accounts;

    public TaskIssueService(TaskIssueRepository issues, TaskRepository tasks, AuthAccountRepository accounts) {
        this.issues = issues;
        this.tasks = tasks;
        this.accounts = accounts;
    }

    @Transactional
    public TaskIssueResponse submit(CurrentUser user, TaskIssueRequest request) {
        if (user.role() != CurrentUser.Role.USER) throw new ForbiddenException("자가진단 회원만 문제 건의를 제출할 수 있습니다.");
        Task task = tasks.findByCode(request.taskId())
                .filter(t -> t.getUsage() == Task.Usage.SELF && t.getStatus() != Task.Status.HIDDEN)
                .orElseThrow(() -> new java.util.NoSuchElementException("과제를 찾을 수 없습니다."));
        AuthAccount account = accounts.findById(user.userId())
                .filter(AuthAccount::isActive)
                .orElseThrow(() -> new ForbiddenException("활성 회원만 문제 건의를 제출할 수 있습니다."));
        String questionNumber = request.questionNumber() == null || request.questionNumber().isBlank()
                ? null : request.questionNumber().trim();
        TaskIssue issue = issues.save(new TaskIssue(task, account, request.category(), questionNumber,
                request.message().trim(), Instant.now()));
        return response(issue);
    }

    @Transactional(readOnly = true)
    public TaskIssuePageResponse list(String taskCode, TaskIssue.Category category, TaskIssue.Status status, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page 는 0 이상이어야 합니다.");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size 는 1~100 사이여야 합니다.");
        String filterTask = taskCode == null || taskCode.isBlank() ? null : taskCode.trim();
        Page<TaskIssue> result = issues.search(filterTask, category, status,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new TaskIssuePageResponse(result.getContent().stream().map(this::adminResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AdminTaskIssueResponse changeStatus(long id, TaskIssue.Status status) {
        TaskIssue issue = issues.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("건의를 찾을 수 없습니다."));
        issue.changeStatus(status, Instant.now());
        return adminResponse(issue);
    }

    private TaskIssueResponse response(TaskIssue issue) {
        return new TaskIssueResponse(issue.getId(), issue.getTask().getCode(), issue.getTask().getTitle(),
                issue.getCategory().name(), issue.getQuestionNumber(), issue.getMessage(), issue.getStatus().name(), issue.getCreatedAt());
    }

    private AdminTaskIssueResponse adminResponse(TaskIssue issue) {
        return new AdminTaskIssueResponse(issue.getId(), issue.getTask().getCode(), issue.getTask().getTitle(),
                issue.getCategory().name(), issue.getQuestionNumber(), issue.getMessage(), issue.getStatus().name(),
                issue.getSubmittedBy().getName(), issue.getSubmittedBy().getEmail(), issue.getCreatedAt(), issue.getUpdatedAt());
    }
}
