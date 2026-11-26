package com.pair.domain.exam.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 새로 저장하면 201 {id, duplicate:false}, 이미 있으면 200 {id, duplicate:true, updated} (updated: 더 긴 durationMs 로 갱신했는지) */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisionEventResponse(Long id, boolean duplicate, Boolean updated) {}
