package com.pair.domain.scoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 다른 패키지의 테스트가 채점 결과 저장(saveResult)을 AI 호출 없이 돌리게 하는 도우미 */
public final class ScoringTestAccess {

    private ScoringTestAccess() {}

    /** 역량 항목 하나짜리 가짜 리포트로 saveResult 를 돌린다 */
    public static void saveFakeResult(ScoringService scoring, long scoringId, String attemptId) {
        var u0 = new ScoringService.CheckOutcome("U0", "라벨", "AUTO", 20, 0, "PASSED", List.of(), null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 2);
        m.put("total", 100);
        m.put("items", List.of(new ScoringService.ItemResult("usage", "AI 활용력", 20, 20, List.of(u0))));
        m.put("judgeCalls", List.<ScoringService.JudgeUsage>of());
        scoring.saveResult(scoringId, attemptId, m);
    }
}
