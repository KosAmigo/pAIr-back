package com.pair.domain.taskissue.dto;

import java.time.Instant;

public record AdminTaskIssueResponse(long id, String taskId, String taskTitle, String category, String questionNumber,
        String message, String status, String submitterName, String submitterEmail, Instant createdAt, Instant updatedAt) {}
