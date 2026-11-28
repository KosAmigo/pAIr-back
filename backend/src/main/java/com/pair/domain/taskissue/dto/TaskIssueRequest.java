package com.pair.domain.taskissue.dto;

import com.pair.domain.taskissue.entity.TaskIssue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskIssueRequest(
        @NotBlank @Size(max = 50) String taskId,
        @NotNull TaskIssue.Category category,
        @Size(max = 50) String questionNumber,
        @NotBlank @Size(min = 5, max = 2000) String message) {}
