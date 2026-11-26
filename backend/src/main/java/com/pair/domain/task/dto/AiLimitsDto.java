package com.pair.domain.task.dto;

/**
 * 과제별 응시 AI 제한(기준 문서 pAIr-docs 설계/좋은_과제_기준.md 6장, E6·M6).
 * turns: 응시 전체 질문 수, maxAttach: 질문 한 번에 붙일 수 있는 파일 수, contextChars: 질문 한 번에 붙인 첨부 글자 합계.
 * 과제 settings 에 값이 없으면(옛 과제) null 이고, 그때는 전역 설정(pair.assistant.*)을 쓴다.
 */
public record AiLimitsDto(int turns, int maxAttach, int contextChars) {

    /** 제한시간별 기본값: 20분 10/2/8000, 40분 16/3/12000, 60분 24/3/16000. 그 밖의 시간은 가까운 쪽 */
    public static AiLimitsDto forMinutes(int minutes) {
        if (minutes <= 30) return new AiLimitsDto(10, 2, 8000);
        if (minutes <= 50) return new AiLimitsDto(16, 3, 12000);
        return new AiLimitsDto(24, 3, 16000);
    }

    /** 세 값이 모두 있을 때만 제한으로 본다(하나라도 없거나 0 이하면 null → 전역 설정) */
    public static AiLimitsDto ofOrNull(Integer turns, Integer maxAttach, Integer contextChars) {
        if (turns == null || maxAttach == null || contextChars == null || turns < 1 || maxAttach < 1 || contextChars < 1) return null;
        return new AiLimitsDto(turns, maxAttach, contextChars);
    }
}
