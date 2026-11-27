package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * POST /api/admin/task-gen/auto: topics 가 있으면 그대로 쓰고(개수가 곧 count), 없으면 서버가 미리보기와 같은 방법으로 count 개를 고른다.
 * usage 는 SELF(기본)/LIVE, mode 는 NOW(기본)/BATCH, batchDeadlineHours 는 BATCH 일 때만 3/6/12/24(기본 3)
 */
public record TaskGenAutoRequest(String track, Integer minutes, String usage, Integer count, String mode, Integer batchDeadlineHours,
        List<AutoTopicDto> topics) {}
