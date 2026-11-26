package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 단독 풀이를 과제의 AI 제한 안에서 돌리는 도구(E6·M6, 기준 문서 3장 난이도 목표).
 * 2026-11-26 g8부터 기본은 pick: AI 가 과제 글과 파일 목록(경로·글자 수)을 보고 붙일 파일을 직접 고르고, 그 순서대로 첨부 개수·맥락 글자 한도까지 붙인다
 * (사람 없이 AI 만 쓰는 응시자 흉내, 질문 한 번). 고르기가 실패하면 attach(이름순 앞에서부터, 모델 안 부름)로 붙인다.
 */
final class SoloLimitService {

    private SoloLimitService() {}

    /** 자료 하나(경로, 글) */
    record Material(String path, String text) {}

    /** 붙인 자료(글을 자른 것 포함)와 붙이지 못한 파일 경로 */
    record Attached(List<Material> attached, List<String> omitted, int chars) {}

    /** limits 가 null 이면 모두 붙인다(옛 동작) */
    static Attached attach(List<Material> files, AiLimitsDto limits) {
        List<Material> ordered = limits == null ? files : byName(files);
        List<Material> out = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int chars = 0;
        for (Material m : ordered) {
            if (m == null || m.text() == null) continue;
            if (limits == null) {
                out.add(m);
                chars += m.text().length();
                continue;
            }
            int room = limits.contextChars() - chars;
            if (out.size() >= limits.maxAttach() || room <= 0) {
                omitted.add(m.path());
                continue;
            }
            String t = m.text().length() > room ? m.text().substring(0, room) : m.text();
            out.add(new Material(m.path(), t));
            chars += t.length();
        }
        return new Attached(out, omitted, chars);
    }

    /**
     * 응시 화면 파일 목록과 같은 이름순(폴더 먼저, 대소문자 무시). 필요한 파일이 앞에 놓이지 않게 해서 '앞에서부터 붙이기'가 곧 정답 고르기가 되지 않게 한다(M6)
     */
    static List<Material> byName(List<Material> files) {
        List<Material> sorted = new ArrayList<>(files);
        sorted.sort((x, y) -> compareTree(x == null || x.path() == null ? "" : x.path(), y == null || y.path() == null ? "" : y.path()));
        return sorted;
    }

    /** 폴더 단계마다 폴더가 파일보다 먼저, 이름은 대소문자 무시 */
    private static int compareTree(String a, String b) {
        String[] x = a.split("/"), y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean dx = i < x.length - 1, dy = i < y.length - 1;
            if (dx != dy) return dx ? -1 : 1;
            int c = x[i].compareToIgnoreCase(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    /** 검사 설명에 붙일 한 줄: 무엇을 붙이고 무엇을 못 붙였는지 */
    static String note(Attached a, AiLimitsDto limits) {
        if (limits == null) return "";
        return "제한(턴 " + limits.turns() + "·첨부 " + limits.maxAttach() + "개·" + limits.contextChars() + "자) 안에서 이름순으로 앞에서부터 붙임: "
                + a.attached().stream().map(Material::path).toList() + (a.omitted().isEmpty() ? "" : ", 못 붙인 파일 " + a.omitted()) + ". ";
    }

    // ── 2026-11-26 g8: AI 가 파일 목록을 보고 직접 골라 붙인다(사람 없이 AI 만 쓰는 응시자 흉내). 이름순 앞에서부터 붙이면 BIZ 는 필요한 자료를 하나도 못 붙였다(g7) ──

    /** AI 가 고른 결과: 붙인 자료, 고른 경로(받은 그대로), 고르기에 쓴 토큰, 고르기가 실패해 이름순으로 붙였는지 */
    record Picked(Attached attached, List<String> chosen, long inputTokens, long outputTokens, boolean fallback) {}

    /** 고르기 질문: 과제 글 + 파일 목록(경로·글자 수, 이름순) + 한도. 파일 내용은 넣지 않는다 */
    static String pickPrompt(String taskText, List<Material> files, AiLimitsDto limits) {
        StringBuilder sb = new StringBuilder(taskText).append("\n\n[파일 목록] (경로, 글자 수)\n");
        for (Material m : byName(files)) sb.append("- ").append(m.path()).append(" (").append(m.text() == null ? 0 : m.text().length()).append("자)\n");
        return sb.append("\n이 과제를 AI 어시스턴트와 풀려고 합니다. 질문 한 번에 파일을 ").append(limits.maxAttach()).append("개까지, 글자 합계 ")
                .append(limits.contextChars()).append("자까지 붙일 수 있습니다. 풀이에 가장 필요한 파일을 골라 files 에 경로를 중요한 순서대로 담으세요.").toString();
    }

    /** AI 가 고른 경로 순서대로 붙인다. 없는 경로·겹친 경로는 버리고, 개수·글자 한도를 넘으면 거기서 자른다(넘는 파일은 남은 글자만큼만) */
    static Attached attachChosen(List<Material> files, List<String> chosen, AiLimitsDto limits) {
        java.util.Map<String, Material> byPath = new java.util.LinkedHashMap<>();
        for (Material m : files) if (m != null && m.path() != null && m.text() != null) byPath.put(m.path(), m);
        List<Material> out = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int chars = 0;
        for (String p : chosen == null ? List.<String>of() : chosen) {
            String path = p == null ? "" : p.strip();
            Material m = byPath.get(path);
            if (m == null || !seen.add(path)) continue;
            int room = limits.contextChars() - chars;
            if (out.size() >= limits.maxAttach() || room <= 0) {
                omitted.add(path);
                continue;
            }
            String t = m.text().length() > room ? m.text().substring(0, room) : m.text();
            out.add(new Material(path, t));
            chars += t.length();
        }
        return new Attached(out, omitted, chars);
    }

    /** 고르기 질문을 한 번 부르고 고른 파일을 붙인다. 호출이 실패하거나 고른 파일이 하나도 없으면 이름순으로 붙인다(fallback) */
    static Picked pick(TaskGenAiService ai, String model, String system, int maxTokens, String effort, String taskText, List<Material> files, AiLimitsDto limits) {
        if (limits == null) return new Picked(attach(files, null), List.of(), 0, 0, false);
        int total = files.stream().filter(m -> m != null && m.text() != null).mapToInt(m -> m.text().length()).sum();
        if (files.size() <= limits.maxAttach() && total <= limits.contextChars()) { // 다 붙일 수 있으면 고를 것이 없다(모델을 부르지 않는다)
            Attached all = attach(files, limits);
            return new Picked(all, all.attached().stream().map(Material::path).toList(), 0, 0, false);
        }
        try {
            var o = ai.generate(model, system, pickPrompt(taskText, files, limits), com.pair.domain.taskgen.dto.SoloPickDto.class, maxTokens, effort);
            List<String> chosen = o.value() == null || o.value().files() == null ? List.of() : o.value().files();
            Attached a = attachChosen(files, chosen, limits);
            if (!a.attached().isEmpty()) return new Picked(a, chosen, o.inputTokens(), o.outputTokens(), false);
            return new Picked(attach(files, limits), chosen, o.inputTokens(), o.outputTokens(), true);
        } catch (RuntimeException e) {
            return new Picked(attach(files, limits), List.of(), 0, 0, true);
        }
    }

    /** 검사 설명에 붙일 한 줄: AI 가 무엇을 골라 붙였는지 */
    static String note(Picked p, AiLimitsDto limits) {
        if (limits == null) return "";
        if (p.fallback()) return "AI 가 파일을 고르지 못해 이름순으로 붙임(" + p.chosen() + "): " + note(p.attached(), limits).replace("안에서 이름순으로 앞에서부터 붙임: ", "");
        return "제한(턴 " + limits.turns() + "·첨부 " + limits.maxAttach() + "개·" + limits.contextChars() + "자) 안에서 AI 가 고른 파일: "
                + p.attached().attached().stream().map(Material::path).toList() + (p.attached().omitted().isEmpty() ? "" : ", 골랐지만 한도로 못 붙인 파일 " + p.attached().omitted()) + ". ";
    }
}
