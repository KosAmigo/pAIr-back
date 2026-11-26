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
    private static final Pattern CSV_PATH = Pattern.compile("자료/[\\p{L}\\p{N}_ ()\\-]+\\.csv");
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
        for (var c : nz(b.cases())) required.add(c.basis()); // 사례의 근거는 반드시 응시자 자료에 들어간다(M2)
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
                nz(b.questionPlan()), nz(b.cases()), nz(b.neededFiles()).stream().filter(x -> !blank(x)).map(String::strip).distinct().toList(), fillers(b));
    }

    /** 관련 적은 자료 목록: 빈 항목과 같은 경로 중복을 버린다 */
    private static List<BlueprintDto.Filler> fillers(BlueprintDto b) {
        List<BlueprintDto.Filler> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var f : nz(b.fillers())) {
            if (f == null || blank(f.path()) || !seen.add(f.path().strip())) continue;
            out.add(new BlueprintDto.Filler(f.path().strip(), f.summary() == null ? "" : f.summary().strip()));
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

    /**
     * E6·M6: 필요한 자료 묶음(neededFiles)과 관련 적은 자료 목록(fillers)이 6장 시간별 분량에 맞는가. problems 와 따로 둔다(켜진 때만 부른다).
     * 파일이 실제로 만들어진 뒤의 글자 수 검사는 MaterialGateService 가 한다.
     */
    static List<String> materialProblems(BlueprintDto b, boolean biz, int minutes) {
        List<String> bad = new ArrayList<>();
        int[] r = materialRange(biz, minutes);
        List<String> needed = nz(b.neededFiles());
        List<BlueprintDto.Filler> fillers = nz(b.fillers());
        if (needed.size() < r[0] || needed.size() > r[1]) {
            bad.add("필요한 자료 묶음(neededFiles)은 " + minutes + "분 과제에서 " + (r[0] == r[1] ? r[0] + "개" : r[0] + "~" + r[1] + "개") + "여야 합니다(지금 " + needed.size() + "개). 풀이에 꼭 필요한 파일 이름만 적습니다");
        }
        List<String> planned = new ArrayList<>(); // 설계도가 이미 정한 파일(BIZ: 문서·표)
        if (biz) {
            nz(b.docs()).forEach(d -> planned.add(d.path()));
            nz(b.tables()).forEach(t -> planned.add(t.path()));
            for (String n : needed) if (!planned.contains(n)) bad.add("필요한 묶음 파일이 문서 계획(docs)·표(tables)에 없습니다: " + n);
        } else {
            for (String n : needed) {
                if (!(n.startsWith("src/") || n.startsWith("test/")) || !n.endsWith(".java")) {
                    if (!n.equals("NOTES.md")) bad.add("필요한 묶음 파일 경로는 src/…java, test/…java, NOTES.md 여야 합니다: " + n);
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>(needed);
        for (var f : fillers) {
            if (blank(f.summary())) bad.add("관련 적은 자료 " + f.path() + " 에 한 줄 내용(summary)이 있어야 합니다");
            if (needed.contains(f.path()) || planned.contains(f.path())) bad.add("관련 적은 자료가 필요한 묶음·설계도 자료와 같은 경로입니다: " + f.path());
            if (biz ? !f.path().matches("자료/[\\p{L}\\p{N}_ ()\\-]+\\.(pdf|csv)") : !(f.path().startsWith("src/") && f.path().endsWith(".java") && !f.path().contains(".."))) {
                bad.add("관련 적은 자료 경로 형식이 맞지 않습니다: " + f.path() + (biz ? " (자료/이름.pdf 또는 자료/이름.csv)" : " (src/…java)"));
            }
            seen.add(f.path());
        }
        seen.addAll(planned);
        int total = biz ? seen.size() : needed.size() + fillers.size();
        int lo = biz ? r[2] : r[2] - 2, hi = r[3]; // DEV 는 공개 테스트·NOTES 처럼 목록에 안 적는 파일이 있을 수 있다
        if (fillers.isEmpty() || total < lo || total > hi) {
            bad.add((biz ? "자료 전체(문서·CSV·관련 적은 자료)" : "필요한 묶음과 관련 적은 자료의 합") + "는 " + minutes + "분 과제에서 " + lo + "~" + hi + "개여야 합니다(지금 " + total + "개). 관련 적은 자료(fillers)를 "
                    + (total < lo ? "더하세요" : "줄이세요"));
        }
        return bad;
    }

    /** 설계도가 다음 단계로 넘어갈 만한지. 문제 문장 목록(없으면 빈 목록). normalized 를 거친 설계도를 받는다 */
    static List<String> problems(BlueprintDto b, boolean biz) {
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
        List<String> caseNames = b.cases().stream().map(BlueprintDto.Case::name).toList();
        // BIZ 는 caughtBy 로 "결론"(결론 문항)도 쓸 수 있다
        if (b.flaws().stream().anyMatch(f -> !blank(f.caughtBy()) && !(biz && f.caughtBy().contains("결론")) && caseNames.stream().noneMatch(n -> n != null && f.caughtBy().contains(n)))) {
            bad.add("결함의 caughtBy 는 사례(cases)의 name 중 하나여야 합니다");
        }
        for (var f : b.flaws()) {
            if (biz || blank(f.wrongAnswer())) continue; // BIZ 는 문항의 흔한 오답(보기·commonWrongAnswers)이 정답과 가른다. 이 요구는 DEV 숨은 테스트용
            boolean separated = !blank(f.separatedBy()) && caseNames.contains(f.separatedBy().strip());
            if (!separated || f.separatedBy().strip().equals(f.caughtBy() == null ? "" : f.caughtBy().strip())) {
                bad.add("결함 '" + f.what() + "' 의 오답(" + f.wrongAnswer() + ")을 정답과 가르는 사례가 필요합니다: separatedBy 는 caughtBy 와 다른 사례(cases)의 name 이어야 하고, "
                        + "그 사례는 오답 방식과 정답의 결과가 달라지는 입력과 기대 결과, 근거 문장(보이는 examples 에 있는 그대로)을 가져야 합니다");
            }
        }
        if (!biz) {
            bad.addAll(addedMethodCases(b));
            bad.addAll(pinnedJudgments(b)); // BIZ 는 문항 정답이 숫자표로 정해지고 판단 지점은 기획안에서 채점하므로 보지 않는다
            if (b.api().isEmpty()) bad.add("공개 API(api)가 있어야 합니다");
            for (String a : b.api()) {
                if (a != null && (a.contains("{") || a.contains("}") || a.contains(";"))) bad.add("api 항목에는 시그니처만 쓰고 본문({ ... })이나 ;는 쓰지 않습니다: " + a);
            }
            if (b.examples().size() < 2 || b.examples().stream().anyMatch(e -> blank(e.input()) || blank(e.expected()))) bad.add("입력→기대 출력 예(examples)가 2개 이상 있어야 합니다");
            return bad;
        }
        if (b.docs().size() < 2 || b.docs().stream().anyMatch(d -> blank(d.path()) || !d.path().matches("자료/[\\p{L}\\p{N}_ ()\\-]+\\.pdf"))) bad.add("문서 계획(docs)이 2개 이상, 경로는 자료/이름.pdf 여야 합니다");
        if (b.tables().isEmpty()) bad.add("숫자표의 표(tables)가 1개 이상 있어야 합니다");
        for (var t : b.tables()) {
            if (t.path() == null || !CSV_PATH.matcher(t.path()).matches()) bad.add("표 경로는 자료/이름.csv 여야 합니다: " + t.path());
            else if (nz(t.columns()).isEmpty() || nz(t.rows()).isEmpty() || t.rows().stream().anyMatch(r -> r.size() != t.columns().size())) bad.add("표 " + t.path() + " 는 머리글과 데이터 행이 있고 칸 수가 같아야 합니다");
        }
        List<String> paths = new ArrayList<>();
        b.tables().forEach(t -> paths.add(t.path()));
        b.docs().forEach(d -> paths.add(d.path()));
        paths.stream().filter(p -> paths.indexOf(p) != paths.lastIndexOf(p)).distinct().forEach(p -> bad.add("자료 경로가 두 번 있습니다: " + p + " (표와 문서 경로는 모두 달라야 합니다)"));
        if (b.conclusion() == null || blank(b.conclusion().correct()) || blank(b.conclusion().wrong())
                || GenTextService.norm(b.conclusion().correct()).equals(GenTextService.norm(b.conclusion().wrong()))) {
            bad.add("정답 결론과 함정에 빠진 결론(conclusion)이 서로 달라야 합니다");
        }
        if (b.flaws().stream().anyMatch(f -> blank(f.evidence()))) bad.add("숨은 결함마다 근거 문장(evidence)이 있어야 합니다");
        if (b.questionPlan().size() < 3) bad.add("문항 계획(questionPlan)이 3개 이상 있어야 합니다");
        for (var c : b.cases()) {
            if (blank(c.ask())) bad.add("사례 " + c.name() + " 에 구할 값(ask)이 있어야 합니다");
            int n = nz(c.choices()).size();
            if (n > 0 && (n < (BizQuestionService.isJudgment(b, c) ? 2 : 3) || (!BizQuestionService.isJudgment(b, c) && BizQuestionService.correctIndex(nz(c.choices()), c.expected()) < 0))) {
                bad.add("사례 " + c.name() + " 의 보기(choices)는 3개 이상이고 정답(expected)과 같은 값이 하나 있어야 합니다(판단 지점은 2개 이상)");
            }
        }
        bad.addAll(BizNumbersService.blueprintProblems(b));
        bad.addAll(optionConflicts(b));
        return bad;
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

    /** DEV: "추가:" 메서드마다 그 메서드를 부르는 사례가 있어야 하고, 결함을 잡는 사례(caughtBy·separatedBy)는 추가 메서드를 부르지 않는다(틀이 던지는 예외로 항상 실패해 결함 검사가 공허해진다) */
    static List<String> addedMethodCases(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String api : nz(b.api())) {
            Object[] sig = FeatureCheckService.addedSignature(api);
            if (sig != null) names.add((String) sig[0]);
        }
        for (String name : names) {
            Pattern call = Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "\\s*\\(");
            if (nz(b.cases()).stream().noneMatch(c -> !blank(c.situation()) && call.matcher(c.situation()).find())) {
                bad.add("추가 메서드 " + name + "() 를 부르는 사례(cases)가 1개 이상 있어야 합니다. situation 에 \"" + name + "(...)\" 호출을 그대로 적고(예: \"할인 1.25, 2.25 등록 뒤 payable(\\\"10.00\\\")\"), "
                        + "basis 는 examples 의 같은 입력→기대 출력. api 는 \"추가: 클래스." + name + "(타입 이름): 반환형\" 꼴로 쓰세요(public·static·패키지 없이)");
            }
            // examples 중 추가 메서드를 부르는 예는 그 입력(따옴표 값·숫자)을 담은 사례가 있어야 한다(없으면 그 동작을 숨은 테스트가 안 본다)
            for (var e : nz(b.examples())) {
                if (blank(e.input()) || !call.matcher(e.input()).find()) continue;
                List<String> literals = literals(e.input());
                boolean covered = nz(b.cases()).stream().anyMatch(c -> {
                    String t = (c.situation() == null ? "" : c.situation()) + " " + (c.basis() == null ? "" : c.basis());
                    return call.matcher(t).find() && literals.stream().allMatch(l -> GenTextService.norm(t).contains(GenTextService.norm(l)));
                });
                if (!covered) bad.add("예 '" + e.input() + " → " + e.expected() + "' 를 확인하는 사례(cases)가 없습니다. 요구사항에 보이는 예마다 그 입력으로 " + name + "() 를 부르는 사례를 하나씩 두세요");
            }
            for (var f : nz(b.flaws())) {
                for (String test : java.util.Arrays.asList(f.caughtBy(), f.separatedBy())) {
                    if (blank(test)) continue;
                    for (var c : nz(b.cases())) {
                        if (c.name() != null && test.contains(c.name()) && !blank(c.situation()) && call.matcher(c.situation()).find()) {
                            bad.add("결함을 잡는 사례 " + c.name() + " 가 추가 메서드 " + name + "() 를 부릅니다. 추가 메서드는 시작 코드에서 항상 예외를 던져 결함과 상관없이 실패하니, 결함 사례는 기존 메서드만 부르게 하세요");
                        }
                    }
                }
            }
        }
        return bad.stream().distinct().toList();
    }

    private static final Pattern LITERAL = Pattern.compile("\"[^\"]*\"|\\d+(?:[.,]\\d+)*");

    /** 입력 글 속 따옴표 값과 숫자("GRID", 1.25 …) */
    private static List<String> literals(String input) {
        List<String> out = new ArrayList<>();
        var m = LITERAL.matcher(input);
        while (m.find()) out.add(m.group().replace("\"", ""));
        return out;
    }

    /** BIZ: 한 문항의 정답 값이 다른 문항의 보기나 문구(구할 값)에 들어 있으면 그 문항을 풀며 이 문항의 답이 드러난다 */
    static List<String> optionConflicts(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (var ci : nz(b.cases())) {
            if (ci.judgment() || blank(ci.expected())) continue;
            String want = GenTextService.norm(ci.expected());
            Pattern token = Pattern.compile("(?<![0-9.])" + Pattern.quote(ci.expected().strip()) + "(?![0-9.])");
            for (var cj : nz(b.cases())) {
                if (cj == ci || cj.name() == null || cj.name().equals(ci.name())) continue;
                if (nz(cj.choices()).stream().anyMatch(ch -> GenTextService.norm(ch).equals(want) || (ValueService.same(ch, ci.expected())))) {
                    bad.add("사례 " + ci.name() + " 의 정답 값(" + ci.expected() + ")이 사례 " + cj.name() + " 의 보기에 있습니다. 다른 문항의 보기에는 이 값을 쓰지 마세요");
                } else if (ci.expected().strip().length() >= 3 && !blank(cj.ask()) && token.matcher(cj.ask()).find()) {
                    bad.add("사례 " + ci.name() + " 의 정답 값(" + ci.expected() + ")이 사례 " + cj.name() + " 의 구할 값 문구에 있습니다");
                }
            }
        }
        return bad.stream().distinct().toList();
    }

    /** DEV: 판단 지점의 선택지 문장이 사례의 근거나 예와 같으면 그 선택으로 결과가 정해져 판단 지점이 선택이 아니게 된다(M4). 판단 지점 문항(judgment)은 제외 */
    static List<String> pinnedJudgments(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (var j : nz(b.judgments())) {
            for (var o : nz(j.options())) {
                String of = GenTextService.norm(o.fact());
                if (of.length() < 10) continue;
                for (var c : nz(b.cases())) {
                    if (c.judgment() || blank(c.basis())) continue;
                    String cb = GenTextService.norm(c.basis());
                    if (cb.length() >= 10 && (cb.contains(of) || of.contains(cb))) {
                        bad.add("사례 " + c.name() + " 의 근거 문장이 판단 지점('" + j.question() + "')의 한 선택지 문장이라 그 선택으로 결과가 정해집니다. 판단 지점은 어느 선택도 인정돼야 하니 그 사례를 빼거나 선택과 상관없는 결과로 바꾸세요");
                    }
                }
                for (var e : nz(b.examples())) {
                    String ex = GenTextService.norm(e.input() + e.expected());
                    if (ex.contains(of)) bad.add("예(" + e.input() + ")가 판단 지점('" + j.question() + "')의 한 선택지 문장을 담아 그 선택으로 정해집니다");
                }
            }
        }
        return bad.stream().distinct().toList();
    }

    /** 정답 쪽 단계에 줄 설계도: 사실 목록은 이미 응시자 글에 들어 있어 뺀다(토큰 절약) */
    static BlueprintDto withoutFacts(BlueprintDto b) {
        return new BlueprintDto(b.title(), b.summary(), b.code(), b.category(), b.competencies(), b.scenario(), b.api(), List.of(), b.examples(),
                b.flaws(), b.judgments(), b.trapKeywords(), b.conclusion(), b.tables(), b.figures(), b.docs(), b.questionPlan(), b.cases(),
                b.neededFiles(), b.fillers());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }
}
