package com.pair.domain.attempt.service;

import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.repository.AttemptRecordRepository;
import com.pair.domain.attempt.repository.AttemptStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.pair.domain.task.repository.TaskRepository;

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

    /**
     * 코드 비교에 쓰는 파일 하나. base 는 과제 원본, a·b 는 두 풀이의 최종 내용이다. 그 파일이 없으면 null
     * (base 가 null 이면 풀이에서 새로 만든 파일). 원본과 A, B 가 모두 같은 파일은 보내지 않는다.
     */
    public record FileDiff(String path, String base, String a, String b) {}

    private final AttemptRecordRepository records;
    private final ReplayReports reports;
    private final AttemptStore store;
    private final TaskRepository tasks;

    public ReplayCompareService(AttemptRecordRepository records, ReplayReports reports, AttemptStore store, TaskRepository tasks) {
        this.records = records;
        this.reports = reports;
        this.store = store;
        this.tasks = tasks;
    }

    /**
     * 두 풀이의 최종 작업 파일을 과제 원본과 함께 돌려준다. 원본과 두 풀이가 모두 같은 파일은 빼고 경로 순으로 준다.
     * 원본을 같이 주는 이유: A→B 차이만 보면 두 풀이가 똑같이 고친 부분이 안 보여서, 각 풀이가 원본에서 무엇을 바꿨는지 알 수 없다.
     * 같은 과제의 풀이끼리만 비교할 수 있고(400), 채점이 끝나지 않아도 된다.
     * 두 응시 모두 내 자가진단 응시여야 하고, 남의 응시는 있는지도 알려 주지 않는다(404).
     */
    @Transactional(readOnly = true)
    public List<FileDiff> files(Long userId, String aId, String bId) {
        AttemptRecord ra = mine(userId, aId);
        AttemptRecord rb = mine(userId, bId);
        if (!ra.getTaskId().equals(rb.getTaskId())) throw new IllegalArgumentException("같은 과제의 풀이끼리만 비교할 수 있습니다.");
        Map<String, String> base = tasks.visibleFiles(store.load(aId).taskId());
        Map<String, String> fa = store.readFiles(aId);
        Map<String, String> fb = store.readFiles(bId);
        Set<String> paths = new TreeSet<>(base.keySet());
        paths.addAll(fa.keySet());
        paths.addAll(fb.keySet());
        List<FileDiff> out = new ArrayList<>();
        for (String path : paths) {
            String o = base.get(path);
            String x = fa.get(path);
            String y = fb.get(path);
            if (!(Objects.equals(o, x) && Objects.equals(o, y))) out.add(new FileDiff(path, o, x, y));
        }
        return out;
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
