package com.pair.domain.stats.dto;

import java.time.Instant;

/**
 * 관리자 회원 목록의 한 줄. 자가진단 수치는 지운 응시를 뺀 전체 기간 값이다.
 *
 * @param status ACTIVE(이용 중) 또는 PENDING(기업 계정 첫 로그인 전)
 * @param companyName 기업 계정이면 회사 이름, 아니면 null
 * @param selfAttempts 자가진단 응시(시작) 수
 * @param selfSubmitted 그중 제출한 수
 * @param averageScore 채점된 자가진단 총점(0~100점) 평균, 소수 첫째 자리. 없으면 null
 * @param creditBalance 크레딧 잔액. 관리자(무제한)와 기업 계정(크레딧을 쓰지 않음)은 null
 * @param lastAttemptAt 마지막 자가진단 시작 시각. 없으면 null
 */
public record AdminMemberResponse(long id, String name, String email, String role, String status, String companyName,
                                  Instant joinedAt, long selfAttempts, long selfSubmitted, Double averageScore,
                                  Integer creditBalance, Instant lastAttemptAt) {}
