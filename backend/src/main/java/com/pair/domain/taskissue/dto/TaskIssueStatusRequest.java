package com.pair.domain.taskissue.dto;

import com.pair.domain.taskissue.entity.TaskIssue;
import jakarta.validation.constraints.NotNull;

public record TaskIssueStatusRequest(@NotNull TaskIssue.Status status) {}
