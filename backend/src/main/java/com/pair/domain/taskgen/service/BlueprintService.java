package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 설계도(1단계) 결과를 코드로 다듬고 확인한다. 모델을 부르지 않는다 */
final class BlueprintService {

    private static final Pattern CODE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    static final Pattern CSV_PATH = Pattern.compile("자료/[\\p{L}\\p{N}_ ()\\-]+\\.csv");
    static final String DESCRIPTION = "description";
    static final String NOTES = "notes";

    private BlueprintService() {}

    /**
     * 빈 목록을 채우고, 판단 근거·선택지별 근거·결함 근거 문장이 사실 목록에 없으면 목록에 더한다(M4: 근거는 반드시 글에 들어간다).
     * 자리(place)가 비었거나 없는 곳이면 기본 자리(DEV: description, BIZ: 첫 문서)로 둔다
     */
    static BlueprintDto normalized(BlueprintDto b, boolean biz) {
        List<BlueprintDto.Doc> docs = nz(b.docs());
        Set<String> places = new LinkedHashSet<>();
        places.add(DESCRIPTION);
        if (biz) docs.forEach(d -> places.add(d.path()));
        else places.add(NOTES);
        String fallback = biz && !docs.isEmpty() ? docs.get(0).path() : DESCRIPTION;

        List<BlueprintDto.Fact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var f : nz(b.facts())) {
            if (f == null || f.text() == null || f.text().isBlank() || !seen.add(GenTextService.norm(f.text()))) continue;
            facts.add(new BlueprintDto.Fact(f.text().strip(), places.contains(f.place()) ? f.place() : fallback));
        }
        List<String> required = new ArrayList<>();
        for (var j : nz(b.judgments())) {
            required.add(j.basis());
            for (var o : nz(j.options())) required.add(o.fact());
        }
        for (var f : nz(b.flaws())) required.add(f.evidence());
        for (var c : nz(b.cases())) { // 사례의 근거는 반드시 응시자 자료에 들어간다(M2)
            if (!biz && ExampleLineService.basisExample(b, c) != null) continue; // 근거가 예면 코드가 요구사항 예 줄로 넣는다(Q7: 설명에 Java 호출 문장을 넣지 않는다)
            required.add(c.basis());
        }
        for (String r : required) {
            if (r != null && !r.isBlank() && seen.add(GenTextService.norm(r))) facts.add(new BlueprintDto.Fact(r.strip(), fallback));
        }
        if (biz && !docs.isEmpty()) { // 결함 근거(evidence)는 문서·CSV 에 있어야 하니(함정 근거 검사) description 자리면 첫 문서로 옮긴다
            Set<String> evidence = new LinkedHashSet<>();
            nz(b.flaws()).forEach(f -> evidence.add(GenTextService.norm(f.evidence())));
            facts = facts.stream().map(f -> DESCRIPTION.equals(f.place()) && evidence.contains(GenTextService.norm(f.text())) ? new BlueprintDto.Fact(f.text(), fallback) : f).toList();
        }
        List<String> api = nz(b.api()).stream().map(a -> a != null && a.contains("{") ? FeatureCheckService.stripBody(a) : a).toList(); // 시그니처에 본문이 붙어 있으면 뗀다
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), nz(b.competencies()), b.scenario(), api, facts,
                nz(b.examples()), nz(b.flaws()), nz(b.judgments()), nz(b.trapKeywords()), b.conclusion(), nz(b.tables()), nz(b.figures()), docs,
                nz(b.questionPlan()), nz(b.cases()), nz(b.neededFiles()).stream().filter(x -> !blank(x)).map(String::strip).distinct().toList(), fillers(b),
                nz(b.exampleSetups()).stream().filter(x -> x != null && !blank(x.method()) && !blank(x.text())).map(x -> new BlueprintDto.ExampleSetup(x.method().strip(), x.text().strip())).toList(),
                b.exampleNote() == null ? "" : b.exampleNote().strip());
    }

    /** 관련 적은 자료 목록: 빈 항목과 같은 경로 중복을 버린다 */
    private static List<BlueprintDto.Filler> fillers(BlueprintDto b) {
        List<BlueprintDto.Filler> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var f : nz(b.fillers())) {
            if (f == null || blank(f.path()) || !seen.add(f.path().strip())) continue;
            out.add(new BlueprintDto.Filler(f.path().strip(), f.summary() == null ? "" : f.summary().strip(), f.confusingFlag()));
        }
        return out;
    }

    /** 제한시간 구간별 6장 분량: {필요 최소, 필요 최대, 전체 최소, 전체 최대}. DEV 는 파일 수, BIZ 는 CSV+문서 수 */
    static int[] materialRange(boolean biz, int minutes) {
        int band = minutes <= 30 ? 0 : minutes <= 50 ? 1 : 2;
        int[][] dev = {{2, 3, 6, 8}, {3, 4, 9, 12}, {4, 5, 12, 15}};
        int[][] bizRange = {{2, 2, 4, 6}, {3, 3, 6, 7}, {3, 4, 7, 9}};
        return (biz ? bizRange : dev)[band];
    }


    /** E6·M6 목록 검사: 직군별로 나눴다(DevBlueprintService·BizBlueprintService). 켜진 때만 부른다 */
    static List<String> materialProblems(BlueprintDto b, boolean biz, int minutes) {
        return biz ? BizBlueprintService.materialProblems(b, minutes) : DevBlueprintService.materialProblems(b, minutes);
    }

    /** 설계도가 다음 단계로 넘어갈 만한지(직군별 형식 검사로 보낸다). 문제 문장 목록(없으면 빈 목록). normalized 를 거친 설계도를 받는다 */
    static List<String> problems(BlueprintDto b, boolean biz) {
        return biz ? BizBlueprintService.problems(b) : DevBlueprintService.problems(b);
    }

    /** 두 직군이 같이 보는 형식 검사(체크리스트 1~6): 제목·상황·code, 사실 수·한 문장, 결함·판단 지점·핵심어·사례의 칸 */
    static List<String> commonProblems(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        if (blank(b.title()) || blank(b.scenario())) bad.add("제목(title)과 상황(scenario)이 있어야 합니다");
        if (b.code() == null || !CODE.matcher(b.code()).matches()) bad.add("code 는 영문 소문자와 하이픈만 쓴 짧은 이름이어야 합니다");
        if (b.facts().size() < 3) bad.add("사실 목록(facts)이 3개 이상 있어야 합니다");
        bad.addAll(multiSentenceFacts(b));
        if (b.flaws().isEmpty() || b.flaws().stream().anyMatch(f -> blank(f.what()) || blank(f.where()) || blank(f.caughtBy()))) {
            bad.add("숨은 결함(flaws)이 1개 이상 있어야 하고 what·where·caughtBy 가 모두 있어야 합니다");
        }
        if (b.judgments().isEmpty() || b.judgments().stream().anyMatch(j -> blank(j.question()) || blank(j.basis()) || nz(j.options()).size() < 2
                || j.options().stream().anyMatch(o -> blank(o.text()) || blank(o.fact())))) {
            bad.add("판단 지점(judgments)이 1개 이상 있어야 하고 question·basis 와 선택지 2개 이상(text, 근거 fact)이 있어야 합니다");
        }
        if (b.trapKeywords().size() < 2 || b.trapKeywords().size() > 5) bad.add("trapKeywords 는 2~5개여야 합니다");
        if (b.cases().size() < b.flaws().size() || b.cases().stream().anyMatch(c -> blank(c.name()) || blank(c.situation()) || blank(c.expected()) || blank(c.basis()))) {
            bad.add("사례(cases)가 결함 수 이상 있어야 하고 name·situation·expected·basis 가 모두 있어야 합니다");
        }
        return bad;
    }

    /** E6·M6 공통: 필요한 묶음 개수 */
    static void neededCount(List<String> bad, BlueprintDto b, int[] r, int minutes) {
        int n = nz(b.neededFiles()).size();
        if (n < r[0] || n > r[1]) {
            bad.add("필요한 자료 묶음(neededFiles)은 " + minutes + "분 과제에서 " + (r[0] == r[1] ? r[0] + "개" : r[0] + "~" + r[1] + "개") + "여야 합니다(지금 " + n + "개). 풀이에 꼭 필요한 파일 이름만 적습니다");
        }
    }

    /** E6·M6 공통: 관련 적은 자료마다 한 줄 내용, 다른 자료와 경로가 겹치지 않음, 경로 꼴(pathOk 와 hint 는 직군별) */
    static void fillerShape(List<String> bad, BlueprintDto b, List<String> planned, java.util.function.Predicate<String> pathOk, String hint) {
        List<String> needed = nz(b.neededFiles());
        for (var f : nz(b.fillers())) {
            if (blank(f.summary())) bad.add("관련 적은 자료 " + f.path() + " 에 한 줄 내용(summary)이 있어야 합니다");
            if (needed.contains(f.path()) || planned.contains(f.path())) bad.add("관련 적은 자료가 필요한 묶음·설계도 자료와 같은 경로입니다: " + f.path());
            if (!pathOk.test(f.path())) bad.add("관련 적은 자료 경로 형식이 맞지 않습니다: " + f.path() + " (" + hint + ")");
        }
    }

    /** E6·M6 공통: 파일 개수가 [lo, hi] 이고 관련 적은 자료가 1개 이상 */
    static void fileCount(List<String> bad, BlueprintDto b, int total, int lo, int hi, int minutes, String what) {
        if (nz(b.fillers()).isEmpty() || total < lo || total > hi) {
            bad.add(what + "는 " + minutes + "분 과제에서 " + lo + "~" + hi + "개여야 합니다(지금 " + total + "개). 관련 적은 자료(fillers)를 " + (total < lo ? "더하세요" : "줄이세요"));
        }
    }

    /** 사실(판단·결함·사례의 근거 문장 포함)은 한 문장씩이다. 여러 문장을 한 사실로 이으면 작성 단계가 쪼개거나 바꿔 써 "그대로 들어감" 검사가 되풀이해 실패한다 */
    static List<String> multiSentenceFacts(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (var f : nz(b.facts())) {
            long n = java.util.Arrays.stream(f.text().split("\s/\s")).mapToLong(part -> TaskWritingService.sentences(part).size()).sum();
            if (n > 1) {
                bad.add("사실·근거 하나에 문장이 " + n + "개입니다: '" + (f.text().length() > 40 ? f.text().substring(0, 40) + "…" : f.text())
                        + "'. 사실, 판단 근거·선택지 fact, 결함 evidence, 사례 basis 는 문장 하나씩만 쓰고, 여러 기록은 사실을 따로 나눠 적으세요(줄바꿈이나 '/' 로 잇지 않기)");
            }
        }
        return bad;
    }

    /** 정답 쪽 단계에 줄 설계도: 사실 목록은 이미 응시자 글에 들어 있어 뺀다(토큰 절약) */
    static BlueprintDto withoutFacts(BlueprintDto b) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), List.of(), b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases(),
                b.neededFiles(), b.fillers(), b.exampleSetups(), b.exampleNote());
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static <T> List<T> nz(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }
}
