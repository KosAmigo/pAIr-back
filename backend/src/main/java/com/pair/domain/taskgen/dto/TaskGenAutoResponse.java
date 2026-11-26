package com.pair.domain.taskgen.dto;

import java.util.List;

/** 202 응답: 자동 생성 묶음 */
public record TaskGenAutoResponse(String batchId, List<String> jobIds) {}
