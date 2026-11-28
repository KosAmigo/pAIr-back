package com.pair.domain.stats.dto;

import java.time.LocalDate;
import java.util.List;

import com.pair.domain.exam.dto.SessionAiCostResponse;

/**
 * 관리자 통계. 기간(from~to)은 한국 시간 날짜이고 to 는 그날 끝까지 포함한다.
 * members 의 users·companyMembers·companies 는 지금 값이고, 나머지는 모두 기간 안의 값이다.
 *
 * @param members 회원
 * @param selfDiagnosis 자가진단 (기간 안에 시작한 응시)
 * @param live 실전 (기간 안에 시작하는 회차, 기간 안에 시작한 응시)
 * @param ai AI 호출 토큰과 예상 비용
 * @param credits 크레딧 결제·환불
 */
public record AdminStatsResponse(LocalDate from, LocalDate to, Members members, Exams selfDiagnosis, LiveExams live,
                                 Ai ai, Credits credits) {

    /**
     * @param users 탈퇴하지 않은 일반 회원 수 (지금)
     * @param companyMembers 탈퇴하지 않은 기업 계정(매니저·감독관) 수 (지금)
     * @param companies 삭제하지 않은 회사 수 (지금)
     * @param newUsers 기간 안에 가입한 일반 회원 수
     * @param withdrawn 기간 안에 탈퇴한 계정 수 (회사 삭제로 정리된 멤버 포함)
     */
    public record Members(long users, long companyMembers, long companies, long newUsers, long withdrawn) {}

    /**
     * 응시 수와 점수. 지운 응시와 관리자의 응시는 뺀다.
     *
     * @param scored 채점 리포트가 나온 응시 수
     * @param averageScore 채점 총점(0~100점) 평균, 소수 첫째 자리. 채점된 응시가 없으면 null
     * @param bands 점수 구간별 응시 수 (0-39, 40-59, 60-79, 80-100)
     * @param tasks 과제별 채점 수와 평균 점수 (채점이 많은 순)
     */
    public record Exams(long started, long submitted, long scored, Double averageScore, List<Band> bands, List<TaskScore> tasks) {}

    /**
     * 과제 하나의 채점 수와 평균 점수 (기간 안에 시작한 응시 기준)
     *
     * @param taskId 과제 코드 (화면 주소에 쓰는 ID)
     */
    public record TaskScore(String taskId, String title, long scored, Double averageScore) {}

    /** 실전: 회차·대상자 수와 응시(대상자 × 과제) 수·점수 */
    public record LiveExams(long sessions, long participants, long started, long submitted, long scored,
                            Double averageScore, List<Band> bands) {}

    public record Band(String range, long count) {}

    /**
     * AI 예상 비용(USD). 채팅·채점은 설정 단가로 계산한 값(회차 AI 비용과 같은 계산), 과제 생성은 작업에 기록된 비용.
     * 단가가 없는 모델은 합계에서 빠지고 unpricedModels 에 이름이 들어간다.
     */
    public record Ai(double totalUsd, SessionAiCostResponse.Part selfChat, SessionAiCostResponse.Part liveChat,
                     SessionAiCostResponse.Part scoring, TaskGen taskGen, List<String> unpricedModels) {}

    /** 과제 생성: 작업 수, 모델 호출 수·토큰, 작업에 기록된 비용 합계 */
    public record TaskGen(long jobs, long calls, long inputTokens, long outputTokens, long cachedTokens, double usd) {}

    /**
     * @param paidCount 기간 안에 결제가 끝난 건수 (뒤에 환불됐어도 포함)
     * @param refundedCount 기간 안에 환불이 끝난 건수
     */
    public record Credits(long paidCount, long paidKrw, long refundedCount, long refundedKrw) {}
}
