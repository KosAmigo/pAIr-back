package com.pair.domain.taskgen.dto;

import java.util.List;

/** POST /api/admin/task-gen(직접 만들기): 기존 폼 + mode(NOW 기본/BATCH), batchDeadlineHours(BATCH 일 때만 1/3/6, 기본 3) */
public record TaskGenStartRequest(String track, String topic, String trap, String usage, Integer minutes, List<String> competencies,
        String mode, Integer batchDeadlineHours) {

    public TaskGenRequest toForm() {
        return new TaskGenRequest(track, topic, trap, usage, minutes, competencies);
    }
}
