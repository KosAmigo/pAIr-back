package com.pair.domain.attempt.dto;

import java.util.Map;

public record DiagnosticsRequest(Map<String, String> files) {}
