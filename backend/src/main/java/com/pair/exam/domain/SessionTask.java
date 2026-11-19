package com.pair.exam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** 회차에 건 과제 하나 (ERD exam_session_tasks). task_id 는 tasks 의 실전(LIVE) 행, 순서는 1부터 */
@Embeddable
public record SessionTask(
        @Column(name = "task_id", nullable = false) Long taskId,
        @Column(name = "sort_order", nullable = false) short sortOrder) {}
