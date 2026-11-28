package com.pair.domain.taskissue.dto;

import java.util.List;

public record TaskIssuePageResponse(List<AdminTaskIssueResponse> items, int page, int size, long totalElements, int totalPages) {}
