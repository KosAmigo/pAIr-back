package com.pair.domain.company.dto;

/** 회사 삭제 결과: 탈퇴 처리한 멤버 수, 취소한 예정 회차 수, 무효로 한 초대 수 */
public record CompanyDeletionResponse(long companyId, int withdrawnMembers, int canceledSessions, int revokedInvites) {}
