package com.pair.domain.sandbox.dto;

import java.util.Map;

/** 샌드박스 서버에 실행·빌드를 맡기는 요청. sources 는 응시 작업 폴더 기준 상대 경로 → 파일 내용 */
public record SandboxSourcesRequest(Map<String, String> sources) {}
