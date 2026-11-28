package com.pair.domain.stats.dto;

import java.util.List;

/** 관리자 회원 목록 한 페이지. page 는 0부터 */
public record AdminMemberPageResponse(List<AdminMemberResponse> members, int page, int size, long totalElements, int totalPages) {}
