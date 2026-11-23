package com.pair.domain.exam.dto;

/** 회차 과제 하나의 진행 상태. attemptId 는 시작 전이면 null */
public record TaskStatusDto(String taskId, String attemptId, boolean submitted) {}
