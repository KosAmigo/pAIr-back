package com.pair.domain.taskgen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DEV 요구사항의 예시 줄을 설계도에서 코드로 만든다(기준 Q7, 2026-11-28). 작성 모델은 예시를 쓰지 않는다.
 * 줄 모양은 pAIr-front 가 표(매개변수 | 결과)로 그리는 약속이라 바꾸면 화면도 같이 바꾼다:
 * "예시 공통: …"(과제당 0~1줄), "예시 준비: {method,text}"(함수별 0~1줄), "예시: {method,args,expected}"(한 줄 JSON).
 * 예시 줄은 그 함수의 "만들 기능" 줄과 매개변수 줄 뒤에 모으고, 만들 기능 줄이 없는 함수(기존 메서드)의 예시는 맨 뒤에 둔다
 */
final class ExampleLineService {

    static final String NOTE = "예시 공통:";
    static final String SETUP = "예시 준비:";
    static final String EXAMPLE = "예시:";
    static final String SHAPE_CHECK = "예시가 짧은 표 모양임";
    static final int MAX_VALUE_CHARS = 30;
    static final int MAX_LIST_ITEMS = 3;
    static final int MAX_PER_METHOD = 3;
    static final int MAX_TOTAL = 6;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FEATURE_LINE = Pattern.compile("^\\s*만들 기능\\s*:");
    /** 작성 모델이 쓴 예 줄: "예:"·"예시" 로 시작하거나 화살표가 있는 줄 */
    private static final Pattern WRITER_EXAMPLE = Pattern.compile("^\\s*(?:[-*]\\s*)?(?:예시|예\\s*\\d*\\s*[:)])|→");
    private static final Pattern API_METHOD = Pattern.compile("([A-Z]\\w*)\\.([A-Za-z_]\\w*)\\s*\\(");

    private ExampleLineService() {}

    /** 사례 근거가 예 문장(입력 → 기대 출력)이면 그 예, 아니면 null (TaskReviewService.basisKind 와 같은 판정) */
    static BlueprintDto.Example basisExample(BlueprintDto bp, BlueprintDto.Case c) {
        if (bp == null || c == null || c.basis() == null) return null;
        String b = GenTextService.norm(c.basis());
        if (b.isBlank()) return null;
        for (var e : nz(bp.examples())) {
            if (e == null) continue;
            String input = GenTextService.norm(e.input()), expected = GenTextService.norm(e.expected());
            if ((input + expected).equals(b) || (!input.isEmpty() && b.contains(input) && b.contains(expected))) return e;
        }
        return null;
    }

    /** 예 하나의 요구사항 줄. 표 칸(method)이 없는 옛 설계도는 예전 꼴 "입력 → 기대 출력" */
    static String line(BlueprintDto.Example e) {
        if (blank(e.method())) return e.input() + " → " + e.expected();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", e.method().strip());
        List<List<String>> args = new ArrayList<>();
        for (var a : nz(e.args())) {
            if (a == null || a.isEmpty()) continue;
            args.add(List.of(str(a.get(0)), a.size() > 1 ? str(a.get(1)) : ""));
        }
        m.put("args", args);
        m.put("expected", str(e.expected()));
        return EXAMPLE + " " + json(m);
    }

    static String setupLine(BlueprintDto.ExampleSetup s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", s.method().strip());
        m.put("text", s.text().strip());
        return SETUP + " " + json(m);
    }

    /** 작성 모델이 쓴 예 줄인가(코드가 다시 넣으니 뺀다) */
    static boolean writerExampleLine(String line) {
        return line != null && WRITER_EXAMPLE.matcher(line).find();
    }

    /**
     * 작성 모델의 요구사항에서 예 줄을 빼고, 설계도의 예시를 함수별로 모아 만들 기능 줄(과 매개변수 줄) 뒤에 넣는다.
     * 요구사항은 한 줄씩 나눈 목록으로 돌려준다. 예가 없으면 예 줄만 뺀다
     */
    static List<String> withExamples(BlueprintDto bp, List<String> requirements) {
        List<String> lines = new ArrayList<>();
        for (String r : nz(requirements)) {
            if (r == null) continue;
            for (String l : r.split("\\R")) if (!l.isBlank() && !writerExampleLine(l) && !l.strip().startsWith(NOTE)) lines.add(l.strip());
        }
        Map<String, List<String>> blocks = blocks(bp);
        if (blocks.isEmpty()) return lines;
        List<String> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            out.add(l);
            if (!FEATURE_LINE.matcher(l).find()) continue;
            while (i + 1 < lines.size() && lines.get(i + 1).startsWith("-")) out.add(lines.get(++i)); // 매개변수 줄
            String method = matchMethod(l, blocks.keySet(), done);
            if (method != null) {
                out.addAll(blocks.get(method));
                done.add(method);
            }
        }
        blocks.forEach((method, block) -> {
            if (!done.contains(method)) out.addAll(block);
        });
        String note = bp.exampleNote();
        if (!blank(note)) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).startsWith(SETUP) || out.get(i).startsWith(EXAMPLE) || blocksContain(blocks, out.get(i))) {
                    out.add(i, NOTE + " " + note.strip());
                    break;
                }
            }
        }
        return out;
    }

    /** 설명에서 작성 모델이 쓴 예 줄(화살표 줄)을 뺀다. 사실 문장이 든 줄은 남긴다 */
    static List<String> descriptionWithoutExamples(BlueprintDto bp, List<String> description) {
        List<String> out = new ArrayList<>();
        for (String p : nz(description)) {
            if (p == null) continue;
            String kept = String.join("\n", java.util.Arrays.stream(p.split("\n"))
                    .filter(l -> !l.contains("→") || nz(bp.facts()).stream().anyMatch(f -> GenTextService.containsLike(l, f.text()))).toList()).strip();
            if (!kept.isEmpty()) out.add(kept);
        }
        return out;
    }

    /** method → 그 함수의 준비 줄과 예 줄(예 순서대로). 표 칸이 없는 옛 예는 "" 묶음 */
    private static Map<String, List<String>> blocks(BlueprintDto bp) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        Map<String, BlueprintDto.ExampleSetup> setups = new LinkedHashMap<>();
        for (var s : nz(bp.exampleSetups())) if (s != null && !blank(s.method()) && !blank(s.text())) setups.putIfAbsent(s.method().strip(), s);
        for (var e : nz(bp.examples())) {
            if (e == null || (blank(e.input()) && blank(e.method()))) continue;
            String key = blank(e.method()) ? "" : e.method().strip();
            List<String> block = blocks.computeIfAbsent(key, k -> {
                List<String> b = new ArrayList<>();
                if (setups.containsKey(k)) b.add(setupLine(setups.get(k)));
                return b;
            });
            block.add(line(e));
        }
        return blocks;
    }

    private static boolean blocksContain(Map<String, List<String>> blocks, String line) {
        return blocks.values().stream().anyMatch(b -> b.contains(line));
    }

    /** 만들 기능 줄이 가리키는 예 묶음(클래스까지 같으면 우선) */
    private static String matchMethod(String featureLine, Set<String> methods, Set<String> done) {
        String loose = null;
        for (String m : methods) {
            if (m.isEmpty() || done.contains(m)) continue;
            int dot = m.lastIndexOf('.');
            String simple = m.substring(dot + 1);
            if (!Pattern.compile("(?<![\\w])" + Pattern.quote(simple) + "\\s*\\(").matcher(featureLine).find()) continue;
            if (dot > 0 && Pattern.compile("(?<![\\w])" + Pattern.quote(m) + "\\s*\\(").matcher(featureLine).find()) return m;
            if (loose == null) loose = m;
        }
        return loose;
    }

    /**
     * 예시가 Q7 의 짧은 표 모양인지(경고만, 검증 전이라 실패로 두지 않는다): 표시값 하나가 30자 넘음, 목록 4개 이상,
     * 함수당 4개 이상·전체 7개 이상, 예시 method 가 api 에 없음
     */
    static GenCheckResultDto shapeCheck(BlueprintDto bp) {
        List<String> hits = new ArrayList<>();
        Set<String> apiMethods = new LinkedHashSet<>();
        for (String a : nz(bp.api())) {
            if (a == null) continue;
            var m = API_METHOD.matcher(a);
            if (m.find()) apiMethods.add(m.group(1) + "." + m.group(2));
        }
        Map<String, Integer> perMethod = new LinkedHashMap<>();
        int total = 0;
        for (var e : nz(bp.examples())) {
            if (e == null) continue;
            total++;
            String method = blank(e.method()) ? "" : e.method().strip();
            perMethod.merge(method, 1, Integer::sum);
            if (method.isEmpty()) hits.add("표 칸(method) 없는 예: " + clip(e.input()));
            else if (!apiMethods.contains(method)) hits.add("api 에 없는 예시 method " + method);
            List<String> values = new ArrayList<>();
            for (var a : nz(e.args())) if (a != null && a.size() > 1) values.add(str(a.get(1)));
            values.add(str(e.expected()));
            for (String v : values) {
                if (v.length() > MAX_VALUE_CHARS) hits.add(method + " 표시값 " + v.length() + "자: " + clip(v));
                int items = listItems(v);
                if (items > MAX_LIST_ITEMS) hits.add(method + " 목록 " + items + "개: " + clip(v));
            }
        }
        perMethod.forEach((m, n) -> {
            if (n > MAX_PER_METHOD) hits.add((m.isEmpty() ? "(method 없음)" : m) + " 예시 " + n + "개");
        });
        if (total > MAX_TOTAL) hits.add("예시 전체 " + total + "개");
        for (var s : nz(bp.exampleSetups())) {
            if (s != null && !blank(s.method()) && !apiMethods.contains(s.method().strip())) hits.add("api 에 없는 예시 준비 method " + s.method());
        }
        if (hits.isEmpty()) return new GenCheckResultDto(SHAPE_CHECK, true, "예시 " + total + "개가 함수별 짧은 표 모양입니다.", GenCheckResultDto.WARN);
        return new GenCheckResultDto(SHAPE_CHECK, false, "예시가 Q7 표 모양을 벗어납니다: " + String.join(" / ", hits.stream().distinct().limit(8).toList()), GenCheckResultDto.WARN);
    }

    /** 표시값이 목록([...])이면 항목 수, 아니면 0 */
    static int listItems(String v) {
        String t = v == null ? "" : v.strip();
        if (!t.startsWith("[") || !t.endsWith("]")) return 0;
        try {
            var node = JSON.readTree(t);
            if (node.isArray()) return node.size();
        } catch (JsonProcessingException ignored) {
            // JSON 이 아니면 쉼표로 센다
        }
        String inner = t.substring(1, t.length() - 1).strip();
        return inner.isEmpty() ? 0 : inner.split(",").length;
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s) {
        String t = s == null ? "" : s;
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String str(String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
