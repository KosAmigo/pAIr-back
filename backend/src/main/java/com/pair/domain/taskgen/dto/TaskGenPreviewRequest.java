package com.pair.domain.taskgen.dto;

import java.util.List;

/** POST /api/admin/task-gen/auto/preview: count 는 1~10(기본 10), minutes 는 20/40/60(기본 60), exclude 는 이미 본 주제 */
public record TaskGenPreviewRequest(String track, Integer minutes, Integer count, List<String> exclude) {}
