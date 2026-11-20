package com.pair.attempt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 복기에서 두 풀이의 감점 근거를 항목·체크별로 나란히 맞춘다. 점수가 왜 달라졌는지(어느 체크가 감점에서 통과로 바뀌었는지)를 보여 주기 위한 것.
 */
@Service
public class ReplayCompareService {

    /** 한 풀이에서 체크 하나의 결과. status: DEDUCTED 감점, PASSED 통과, SKIPPED 적용 안 함, NO_EVIDENCE 근거 없어 감점 안 함, SPLIT 판정 엇갈림 */
    public record Side(String status, int deducted, String reason, List<String> evidence) {}

    /** a·b 가 null 이면 그 풀이 리포트에 이 체크가 없다. changed: 두 풀이의 상태나 감점이 다르다 */
    public record CheckDiff(String code, String label, String method, int points, Side a, Side b, boolean changed) {}

    public record ItemDiff(String key, String name, int max, Integer a, Integer b, List<CheckDiff> checks) {}

    public record Result(String a, String b, List<ItemDiff> items) {}

    private final AttemptRecordRepository records;
    private final ReplayReports reports;

    public ReplayCompareService(AttemptRecordRepository records, ReplayReports reports) {
        this.records = records;
        this.reports = reports;
    }

    /** 두 응시 모두 내 자가진단 응시이고 채점이 끝나 있어야 한다. 남의 응시는 있는지도 알려 주지 않는다(404) */
    @Transactional(readOnly = true)
    public Result compare(Long userId, String aId, String bId) {
        AttemptRecord a = mine(userId, aId);
        AttemptRecord b = mine(userId, bId);
        JsonNode ra = reports.scored(a).orElseThrow(() -> notScored(aId));
        JsonNode rb = reports.scored(b).orElseThrow(() -> notScored(bId));

        Map<String, JsonNode> itemsB = byKey(rb.path("items"), "key");
        List<ItemDiff> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (JsonNode ia : ra.path("items")) {
            String key = ia.path("key").asText();
            seen.add(key);
            out.add(item(ia, itemsB.get(key)));
        }
        for (JsonNode ib : rb.path("items")) {
            if (!seen.contains(ib.path("key").asText())) out.add(item(null, ib));
        }
        return new Result(aId, bId, out);
    }

    private static ItemDiff item(JsonNode ia, JsonNode ib) {
        JsonNode base = ia != null ? ia : ib;
        Map<String, JsonNode> checksA = ia == null ? Map.of() : byKey(ia.path("checks"), "code");
        Map<String, JsonNode> checksB = ib == null ? Map.of() : byKey(ib.path("checks"), "code");
        Map<String, JsonNode> union = new LinkedHashMap<>(checksA);
        checksB.forEach(union::putIfAbsent);
        List<CheckDiff> checks = new ArrayList<>();
        for (String code : union.keySet()) {
            JsonNode ca = checksA.get(code);
            JsonNode cb = checksB.get(code);
            JsonNode meta = ca != null ? ca : cb;
            Side sa = side(ca);
            Side sb = side(cb);
            boolean changed = !Objects.equals(sa == null ? null : sa.status(), sb == null ? null : sb.status())
                    || (sa != null && sb != null && sa.deducted() != sb.deducted());
            checks.add(new CheckDiff(code, meta.path("label").asText(), meta.path("method").asText(), meta.path("points").asInt(), sa, sb, changed));
        }
        return new ItemDiff(base.path("key").asText(), base.path("name").asText(), base.path("max").asInt(), score(ia), score(ib), checks);
    }

    private static Integer score(JsonNode item) {
        return item != null && item.path("score").isNumber() ? item.path("score").asInt() : null;
    }

    private static Side side(JsonNode c) {
        if (c == null) return null;
        List<String> evidence = new ArrayList<>();
        c.path("evidence").forEach(e -> evidence.add(e.asText()));
        return new Side(c.path("status").asText(), c.path("deducted").asInt(), c.path("reason").isNull() ? null : c.path("reason").asText(null), evidence);
    }

    private static Map<String, JsonNode> byKey(JsonNode array, String field) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        array.forEach(n -> m.put(n.path(field).asText(), n));
        return m;
    }

    private AttemptRecord mine(Long userId, String id) {
        AttemptRecord r;
        try {
            r = records.findById(Long.parseLong(id)).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + id));
        } catch (NumberFormatException e) {
            throw new NoSuchElementException("없는 응시입니다: " + id);
        }
        if (r.getMode() != AttemptRecord.Mode.SELF || !userId.equals(r.getUserId())) throw new NoSuchElementException("없는 응시입니다: " + id);
        return r;
    }

    private static IllegalStateException notScored(String id) {
        return new IllegalStateException("채점이 끝난 풀이만 비교할 수 있습니다. (응시 " + id + ")");
    }
}
