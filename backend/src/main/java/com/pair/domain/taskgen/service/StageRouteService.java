package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 실패한 검사를 낸 단계 찾기. 한 검사가 여러 단계를 가리킬 수 있고, 모르면 빈 집합(= 설계도부터 처음부터) */
final class StageRouteService {

    private StageRouteService() {}

    /** 다시 돌릴 단계들과 단계별 실패 검사. 모르는 검사나 설계도 문제가 하나라도 있으면 설계도부터 전부 */
    record Plan(Set<Stage> stages, Map<Stage, List<GenCheckResultDto>> byStage, List<GenCheckResultDto> all) {

        boolean fromBlueprint() {
            return stages.contains(Stage.BLUEPRINT);
        }
    }

    /**
     * 다시 돌릴 단계 정하기. all: 이 직군의 모든 단계(설계도부터 다시일 때), answerFollows: 이 단계 중 하나가 다시 돌면 정답 쪽도 다시 돌아야 하는 단계
     * (DEV 는 시작 코드, BIZ 는 작성: 정답이 앞 단계 결과에 기대므로). 그 밖의 단계는 실패한 단계만 다시 돈다
     */
    static Plan plan(List<GenCheckResultDto> failed, Function<GenCheckResultDto, Set<Stage>> owner, Set<Stage> all, Set<Stage> answerFollows) {
        Map<Stage, List<GenCheckResultDto>> by = new LinkedHashMap<>();
        boolean restart = failed.isEmpty();
        for (GenCheckResultDto c : failed) {
            Set<Stage> owners = owner.apply(c);
            if (owners.isEmpty() || owners.contains(Stage.BLUEPRINT)) restart = true;
            owners.forEach(s -> by.computeIfAbsent(s, k -> new ArrayList<>()).add(c));
        }
        Set<Stage> stages = EnumSet.noneOf(Stage.class);
        if (restart) {
            stages.addAll(all);
        } else {
            stages.addAll(by.keySet());
            if (answerFollows.stream().anyMatch(stages::contains)) stages.add(Stage.ANSWER);
        }
        return new Plan(stages, by, failed);
    }

    /**
     * 관문 검사 실패가 가리키는 단계. 관문이 아니면 null.
     * 답 노출은 위치(검수자가 적은 곳)를 보고 정한다: 문항·보기 → 문항 글(BIZ), 시작 코드·파일(DEV) → 시작 코드, 그 밖(설명·자료·요구사항)이나 모르면 작성.
     * 정답 키·테스트 근거·다른 풀이 실행은 정답 쪽
     */
    static Set<Stage> gateOwners(GenCheckResultDto c, boolean biz) {
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        String loc = String.join(" ", locations(d)); // 위반 위치 칸만 본다(인용·고칠 방향 글의 낱말에 끌려가지 않게)
        if (n.equals(TaskPipelineService.LEAK_A_GATE) || n.equals(TaskPipelineService.LEAK_B_GATE)) {
            d = loc;
            Set<Stage> s = EnumSet.noneOf(Stage.class);
            if (biz && (d.contains("문항") || d.contains("보기") || d.contains("question:") || d.contains("choice:") || d.matches("(?s).*\\bQ\\d.*"))) s.add(Stage.QUESTIONS);
            if (!biz && (d.contains("src/") || d.contains("test/") || d.contains("시작 코드") || d.contains(".java"))) s.add(Stage.CODE);
            if (s.isEmpty() || d.contains("설명") || d.contains("요구사항") || d.contains("문서") || d.contains("NOTES") || d.contains("CSV")) s.add(Stage.WRITING);
            return s;
        }
        if (n.equals(TaskPipelineService.FILLER_GATE)) return EnumSet.of(Stage.FILLER); // 관련 적은 자료만 다시 만든다(M6)
        if (n.equals(TaskPipelineService.KEY_GATE) || n.equals(TaskPipelineService.BACKING_GATE) || n.equals(TaskPipelineService.ALT_GATE)) {
            Set<Stage> marked = markedStages(c.detail());
            if (!marked.isEmpty()) return marked; // 관문이 직접 적은 고칠 단계
            // 적지 않았으면 기본: 테스트 근거는 자료(작성), 정답 키는 정답 쪽, 다른 풀이가 떨어지면 사례가 너무 엄격한 것(설계도)
            if (n.equals(TaskPipelineService.BACKING_GATE)) return EnumSet.of(Stage.WRITING);
            if (n.equals(TaskPipelineService.KEY_GATE)) return EnumSet.of(Stage.ANSWER);
            return EnumSet.of(Stage.BLUEPRINT);
        }
        return null;
    }

    private static final java.util.regex.Pattern MARK = java.util.regex.Pattern.compile("\\[단계:([A-Z]+)]");
    private static final java.util.regex.Pattern LOC = java.util.regex.Pattern.compile("^\\S+\\s*\\[([^\\]]*)]");

    /** 관문 상세 끝의 [단계:XXX] 표시들 */
    static Set<Stage> markedStages(String detail) {
        Set<Stage> out = EnumSet.noneOf(Stage.class);
        var m = MARK.matcher(detail == null ? "" : detail);
        while (m.find()) {
            try {
                out.add(Stage.valueOf(m.group(1)));
            } catch (IllegalArgumentException ignore) {
                // 모르는 단계 이름은 버린다
            }
        }
        return out;
    }

    /** 위반 글("항목 [위치] 인용 → 방향 / ...")의 위치 칸들. 형식이 아니면 상세 전체 */
    static List<String> locations(String detail) {
        List<String> out = new ArrayList<>();
        if (detail == null) return out;
        for (String seg : detail.split(" / ")) {
            var m = LOC.matcher(seg.strip());
            if (m.find()) out.add(m.group(1));
        }
        if (out.isEmpty()) out.add(detail);
        return out;
    }

    private static final java.util.regex.Pattern QUOTE = java.util.regex.Pattern.compile("]\\s*(.+?)\\s*→");
    private static final int MIN_QUOTE = 10;

    /** 관문 상세("항목 [위치] 인용 → 고칠 방향 / ...")에서 인용한 글들(정규화) */
    static List<String> quotes(String detail) {
        List<String> out = new ArrayList<>();
        if (detail == null) return out;
        for (String seg : detail.split(" / ")) {
            var m = QUOTE.matcher(seg);
            if (m.find()) {
                String q = GenTextService.norm(m.group(1));
                if (q.length() >= MIN_QUOTE) out.add(q);
            }
        }
        return out;
    }

    /** 설계도가 응시자 글에 그대로 넣게 하는 문장들(정규화): 사실, 판단 근거·선택지 문장, 예, 사례 근거, 결함 근거 */
    static List<String> blueprintSentences(com.pair.domain.taskgen.dto.BlueprintDto bp) {
        List<String> raw = new ArrayList<>();
        if (bp == null) return raw;
        bp.facts().forEach(f -> raw.add(f.text()));
        bp.judgments().forEach(j -> {
            raw.add(j.basis());
            j.options().forEach(o -> raw.add(o.fact()));
        });
        bp.examples().forEach(e -> {
            raw.add(e.input());
            raw.add(e.expected());
            raw.add(e.input() + " → " + e.expected());
        });
        bp.cases().forEach(c -> raw.add(c.basis()));
        bp.flaws().forEach(f -> raw.add(f.evidence()));
        return raw.stream().map(GenTextService::norm).filter(s -> s.length() >= MIN_QUOTE).distinct().toList();
    }

    private static boolean isGate(GenCheckResultDto c) {
        String n = c.name();
        return n.equals(TaskPipelineService.LEAK_A_GATE) || n.equals(TaskPipelineService.LEAK_B_GATE) || n.equals(TaskPipelineService.KEY_GATE)
                || n.equals(TaskPipelineService.BACKING_GATE) || n.equals(TaskPipelineService.ALT_GATE);
    }

    /** 같은 검사가 같은 이유로 또 실패했는가(같은 인용 또는 같은 상세) */
    static boolean sameReason(GenCheckResultDto prev, GenCheckResultDto now) {
        if (!prev.name().equals(now.name())) return false;
        if (GenTextService.norm(prev.detail()).equals(GenTextService.norm(now.detail()))) return true;
        List<String> a = quotes(prev.detail());
        return quotes(now.detail()).stream().anyMatch(a::contains);
    }

    /**
     * 글·정답 쪽 단계를 다시 돌려도 고쳐지지 않는 실패를 설계도 실패로 바꾼다: (1) 관문이 인용한 문장이 설계도의 사실·근거·예 문장과 같거나 겹치면
     * (글 단계는 그 문장을 그대로 넣어야 하므로 못 고친다), (2) 바로 앞 다시 만들기 때와 같은 이유로 같은 검사가 또 실패하면 한 단계 위(설계도)로 올린다
     */
    static GenCheckResultDto reroute(com.pair.domain.taskgen.dto.BlueprintDto bp, GenCheckResultDto c, List<GenCheckResultDto> previous) {
        if (c.ok() || c.name().equals(TaskPipelineService.BLUEPRINT_CHECK) || c.name().equals(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK)) return c;
        String hint = null;
        if (isGate(c)) {
            List<String> sentences = blueprintSentences(bp);
            for (String q : quotes(c.detail())) {
                // 인용이 설계도 문장 안에 있거나, 설계도 문장이 인용의 대부분(80% 이상)이면 설계도 문장 탓. 작성자가 길게 덧붙인 말은 작성 탓
                if (sentences.stream().anyMatch(s -> s.contains(q) || (q.contains(s) && s.length() * 100 >= q.length() * 80))) {
                    hint = "관문이 지적한 문장이 설계도의 사실·근거·예 문장입니다. 글 단계는 이 문장을 그대로 넣어야 해서 못 고칩니다. 이 문장이 절차·규칙·계산 순서·원인·고치는 법을 말하면 "
                            + "기록이나 관측(누가 무엇을 봤나, 어떤 입력에 어떤 값이 나왔나)으로 다시 쓰고, 한쪽 선택으로 정해 버리는 문장이면 판단 지점이나 사례에서 빼세요.";
                    break;
                }
            }
        }
        if (hint == null && previous != null && previous.stream().anyMatch(p -> sameReason(p, c))) {
            hint = "같은 검사가 같은 이유로 되풀이해 실패했습니다. 이 단계만 다시 만들어서는 고쳐지지 않으니 설계도(사실·사례·보기·판단 지점)부터 고치세요.";
        }
        if (hint == null) return c;
        return new GenCheckResultDto(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK, false, hint + " [" + c.name() + "] " + c.detail());
    }

    /** 실패 검사들의 문제 종류: 고칠 단계가 글 단계(작성·문항 글)뿐이면 "text", 아니면(정답 쪽·시작 코드·설계도·모름) "test". 둘 다 있으면 둘 다 */
    static Set<String> kinds(List<GenCheckResultDto> failed, Function<GenCheckResultDto, Set<Stage>> owner) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (GenCheckResultDto c : failed) {
            Set<Stage> o = owner == null ? null : owner.apply(c);
            boolean text = o != null && !o.isEmpty() && o.stream().allMatch(s -> s == Stage.WRITING || s == Stage.QUESTIONS);
            out.add(text ? "text" : "test");
        }
        return out;
    }

    /** 설계도·시작 코드·정답 쪽 단계에 주는 안내: 실패한 검사의 이름과 상세 */
    static String feedback(List<GenCheckResultDto> failed) {
        if (failed == null || failed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("앞서 만든 결과는 자동 확인에서 아래 검사에 실패했습니다. 이 문제를 고쳐 이 단계를 다시 만들어 주세요.");
        for (GenCheckResultDto c : failed) sb.append("\n- ").append(c.name()).append(": ").append(c.detail());
        return sb.toString();
    }
}
