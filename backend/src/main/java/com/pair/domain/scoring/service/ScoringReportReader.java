package com.pair.domain.scoring.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pair.domain.scoring.entity.CheckResult;
import com.pair.domain.scoring.repository.CheckResultRepository;
import com.pair.domain.scoring.entity.JudgeCall;
import com.pair.domain.scoring.repository.JudgeCallRepository;
import com.pair.domain.scoring.entity.JudgeVerdict;
import com.pair.domain.scoring.repository.JudgeVerdictRepository;
import com.pair.domain.scoring.repository.PromptVersionRepository;
import com.pair.domain.scoring.entity.RubricCheck;
import com.pair.domain.scoring.repository.RubricCheckRepository;
import com.pair.domain.scoring.entity.RubricItem;
import com.pair.domain.scoring.repository.RubricItemRepository;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.scoring.repository.ScoringRepository;

/**
 * 채점 결과를 채점 테이블에서 읽어 리포트 JSON 의 점수 부분(items, total, max, promptVersion, judgeCalls)을 채운다.
 * 점수와 판정은 DB 가 기준이다. 표에 없는 부분(대화 기록, 안내 문구, 숨은 테스트 결과 등)은 attempt_reports 의 JSON 에서 가져온다.
 * 아직 채점 표에 기록이 없는 옛 응시는 JSON 을 그대로 쓴다.
 */
@Component
public class ScoringReportReader {

    private final ScoringRepository scorings;
    private final CheckResultRepository results;
    private final JudgeCallRepository calls;
    private final JudgeVerdictRepository verdicts;
    private final RubricItemRepository items;
    private final RubricCheckRepository checks;
    private final PromptVersionRepository versions;
    private final ObjectMapper json;

    public ScoringReportReader(ScoringRepository scorings, CheckResultRepository results,
                               JudgeCallRepository calls, JudgeVerdictRepository verdicts,
                               RubricItemRepository items, RubricCheckRepository checks,
                               PromptVersionRepository versions, ObjectMapper json) {
        this.scorings = scorings;
        this.results = results;
        this.calls = calls;
        this.verdicts = verdicts;
        this.items = items;
        this.checks = checks;
        this.versions = versions;
        this.json = json;
    }

    /** @param stored attempt_reports 의 JSON (없으면 null) */
    @Transactional(readOnly = true)
    public JsonNode overlay(long attemptId, JsonNode stored) {
        Scoring scoring = scorings.findFirstByAttemptIdAndStatusOrderByIdDesc(attemptId, "DONE").orElse(null);
        if (scoring == null) return stored;

        List<CheckResult> rs = results.findByScoringId(scoring.getId());
        Map<Long, RubricCheck> checkById = checks.findAllById(rs.stream().map(CheckResult::getCheckId).toList())
                .stream().collect(Collectors.toMap(RubricCheck::getId, c -> c));
        List<JudgeCall> cs = calls.findByScoringId(scoring.getId()).stream()
                .sorted(Comparator.comparing(JudgeCall::getId)).toList();
        Map<Long, String> judgeNames = judgeNames(cs);
        Map<Long, List<JudgeVerdict>> verdictsByResult = verdicts
                .findByResultIdIn(rs.stream().map(CheckResult::getId).toList()).stream()
                .sorted(Comparator.comparing(JudgeVerdict::getId))
                .collect(Collectors.groupingBy(JudgeVerdict::getResultId));
        Map<Long, JudgeCall> callById = cs.stream().collect(Collectors.toMap(JudgeCall::getId, c -> c));

        // 항목별 체크 결과 (체크 id 순 = 기준표 순서)
        Map<String, List<CheckResult>> byItem = new HashMap<>();
        rs.stream().sorted(Comparator.comparing(CheckResult::getCheckId)).forEach(r ->
                byItem.computeIfAbsent(checkById.get(r.getCheckId()).getItemKey(), k -> new ArrayList<>()).add(r));

        ObjectNode report = stored != null && stored.isObject() ? ((ObjectNode) stored).deepCopy() : json.createObjectNode();
        ArrayNode itemArr = json.createArrayNode();
        int total = 0;
        int max = 0;
        List<RubricItem> itemRows = items.findAll().stream().sorted(Comparator.comparing(RubricItem::getSortOrder)).toList();
        for (RubricItem it : itemRows) {
            List<CheckResult> list = byItem.getOrDefault(it.getKey(), List.of());
            int deducted = list.stream().mapToInt(CheckResult::getDeducted).sum();
            int score = Math.max(0, it.getMaxScore() - deducted);
            total += score;
            max += it.getMaxScore();
            ObjectNode itemNode = json.createObjectNode();
            itemNode.put("key", it.getKey());
            itemNode.put("name", it.getName());
            itemNode.put("max", it.getMaxScore());
            itemNode.put("score", score);
            ArrayNode checkArr = itemNode.putArray("checks");
            for (CheckResult r : list) {
                checkArr.add(checkNode(r, checkById.get(r.getCheckId()), verdictsByResult.getOrDefault(r.getId(), List.of()),
                        callById, judgeNames));
            }
            itemArr.add(itemNode);
        }
        report.put("version", 2);
        report.set("items", itemArr);
        report.put("total", total);
        report.put("max", max);
        versions.findById(scoring.getVersionId()).ifPresent(v -> report.put("promptVersion", v.getName()));
        ArrayNode callArr = report.putArray("judgeCalls");
        for (JudgeCall c : cs) {
            ObjectNode n = callArr.addObject();
            n.put("judge", judgeNames.get(c.getId()));
            n.put("model", c.getModel());
            n.put("input", c.getInputTokens());
            n.put("output", c.getOutputTokens());
            n.put("cached", c.getCachedTokens());
        }
        return report;
    }

    private ObjectNode checkNode(CheckResult r, RubricCheck c, List<JudgeVerdict> vs,
                                 Map<Long, JudgeCall> callById, Map<Long, String> judgeNames) {
        ObjectNode n = json.createObjectNode();
        n.put("code", c.getCode());
        n.put("label", c.getLabel());
        n.put("method", c.getMethod());
        n.put("points", c.getPoints());
        n.put("deducted", r.getDeducted());
        n.put("status", r.getStatus());
        // 근거: 걸림/엇갈림은 걸렸다고 한 판정의 근거, 통과는 모든 판정의 근거, 근거 없음은 비움 (ScoringService.combine 과 같은 규칙)
        Set<String> evidence = new LinkedHashSet<>();
        boolean violatedOnly = r.getStatus().equals("DEDUCTED") || r.getStatus().equals("SPLIT");
        if (violatedOnly || r.getStatus().equals("PASSED")) {
            for (JudgeVerdict v : vs) {
                if (!violatedOnly || (v.isViolated() && !v.getEvidences().isEmpty())) evidence.addAll(v.getEvidences());
            }
        }
        ArrayNode ev = n.putArray("evidence");
        evidence.forEach(ev::add);
        if (r.getReason() == null) n.putNull("reason");
        else n.put("reason", r.getReason());
        ArrayNode judges = n.putArray("judges");
        for (JudgeVerdict v : vs) {
            ObjectNode j = judges.addObject();
            j.put("judge", judgeNames.get(v.getJudgeCallId()));
            j.put("model", callById.get(v.getJudgeCallId()).getModel());
            j.put("violated", v.isViolated());
            j.put("count", v.getCount());
            ArrayNode jev = j.putArray("evidence");
            v.getEvidences().forEach(jev::add);
            if (v.getReason() == null) j.putNull("reason");
            else j.put("reason", v.getReason());
        }
        return n;
    }

    /** 호출 id → 화면에 쓰는 채점 모델 이름. 같은 모델을 여러 번 불렀으면 model#1, model#2 처럼 구분한다. */
    private static Map<Long, String> judgeNames(List<JudgeCall> sortedCalls) {
        Map<String, Long> perModel = sortedCalls.stream().collect(Collectors.groupingBy(JudgeCall::getModel, Collectors.counting()));
        Map<String, Integer> seen = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (JudgeCall c : sortedCalls) {
            int n = seen.merge(c.getModel(), 1, Integer::sum);
            names.put(c.getId(), perModel.get(c.getModel()) > 1 ? c.getModel() + "#" + n : c.getModel());
        }
        return names;
    }
}
