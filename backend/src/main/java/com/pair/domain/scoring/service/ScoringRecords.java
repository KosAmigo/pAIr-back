package com.pair.domain.scoring.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.grader.service.PromptVersion;
import com.pair.domain.scoring.service.Rubric.Check;
import com.pair.domain.scoring.entity.CheckResultRecord;
import com.pair.domain.scoring.repository.CheckResultRecordRepository;
import com.pair.domain.scoring.entity.JudgeCallRecord;
import com.pair.domain.scoring.repository.JudgeCallRecordRepository;
import com.pair.domain.scoring.entity.JudgeVerdictRecord;
import com.pair.domain.scoring.repository.JudgeVerdictRecordRepository;
import com.pair.domain.scoring.entity.ScoringRecord;
import com.pair.domain.scoring.repository.ScoringRecordRepository;

/** 실제 응시 채점 결과를 채점 테이블(scorings, judge_calls, check_results, judge_verdicts)에 남긴다. */
@Component
public class ScoringRecords {

    static final int REF_MAX = 50;

    private final ScoringCatalog catalog;
    private final ScoringRecordRepository scorings;
    private final JudgeCallRecordRepository calls;
    private final CheckResultRecordRepository results;
    private final JudgeVerdictRecordRepository verdicts;

    public ScoringRecords(ScoringCatalog catalog, ScoringRecordRepository scorings, JudgeCallRecordRepository calls,
                          CheckResultRecordRepository results, JudgeVerdictRecordRepository verdicts) {
        this.catalog = catalog;
        this.scorings = scorings;
        this.calls = calls;
        this.results = results;
        this.verdicts = verdicts;
    }

    /**
     * 채점을 시작했다고 남기고 scoring_id 를 돌려준다.
     * 트랜잭션을 걸지 않는다. 버전 등록(별도 트랜잭션)을 먼저 끝내고 scorings 행만 짧게 넣어, 채점 1건이 DB 연결을 둘 잡지 않게 한다.
     */
    public long start(long attemptId, PromptVersion v) {
        long versionId = catalog.versionId(v);
        return scorings.save(new ScoringRecord(attemptId, versionId, Instant.now())).getId();
    }

    /** 체크 결과와 모델별 판정을 저장하고 채점을 DONE 으로 바꾼다. */
    @Transactional
    public void finish(long scoringId, boolean dev, List<ScoringService.ItemResult> items, List<ScoringService.JudgeCall> judgeCalls) {
        Map<String, Long> callIds = new HashMap<>();
        for (ScoringService.JudgeCall c : judgeCalls) {
            JudgeCallRecord rec = calls.save(new JudgeCallRecord(scoringId, cut(c.model(), 50),
                    (int) c.input(), (int) c.output(), (int) c.cached()));
            callIds.put(c.judge(), rec.getId());
        }
        List<Check> applicable = Rubric.checksFor(dev);
        for (ScoringService.ItemResult item : items) {
            for (ScoringService.CheckResult r : item.checks()) {
                Check c = applicable.stream().filter(x -> x.code().equals(r.code())).findFirst().orElseThrow();
                long checkId = catalog.checkId(Rubric.key(c));
                CheckResultRecord saved = results.save(new CheckResultRecord(scoringId, checkId, r.deducted(), r.status(), r.reason()));
                for (ScoringService.JudgeView view : r.judges()) {
                    Long callId = callIds.get(view.judge());
                    if (callId == null) continue; // 호출 기록이 없는 판정은 연결할 수 없어 건너뜀
                    Set<String> refs = new LinkedHashSet<>();
                    if (view.evidence() != null) {
                        for (String ref : view.evidence()) {
                            if (ref != null && !ref.isBlank()) refs.add(cut(ref, REF_MAX));
                        }
                    }
                    verdicts.save(new JudgeVerdictRecord(saved.getId(), callId, view.violated(), view.count(), view.reason(), refs));
                }
            }
        }
        scorings.findById(scoringId).orElseThrow().done(Instant.now());
    }

    /** 채점 실패를 남긴다. 바깥 트랜잭션이 롤백돼도 남도록 따로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long scoringId, String error) {
        scorings.findById(scoringId).ifPresent(s -> s.fail(error == null ? "알 수 없는 오류" : error, Instant.now()));
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
