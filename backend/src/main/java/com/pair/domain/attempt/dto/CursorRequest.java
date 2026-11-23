package com.pair.domain.attempt.dto;

import java.util.Map;

/** 커서 앞 "식." 의 멤버 자동 완성. offset 은 path 파일 안의 글자 위치(0부터). */
public record CursorRequest(Map<String, String> files, String path, int offset) {}
