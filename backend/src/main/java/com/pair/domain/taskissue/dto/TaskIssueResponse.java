package com.pair.domain.taskissue.dto;

import java.time.Instant;

public record TaskIssueResponse(long id, String taskId, String taskTitle, String category, String questionNumber,
        String message, String status, Instant createdAt) {}
