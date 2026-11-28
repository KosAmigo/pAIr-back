package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.BlueprintService.blank;
import static com.pair.domain.taskgen.service.BlueprintService.nz;

import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DEV 설계도 형식 검사(2026-11-26 g6: 직군별 설계도). 체크리스트는 TaskPromptService.devBlueprint 끝의 목록과 1:1 이다.
 * 공통 검사는 BlueprintService.commonProblems. 모델을 부르지 않는다
 */
final class DevBlueprintService {

    private DevBlueprintService() {}

    /** DEV 형식 검사: 공통 + 공개 API·예·추가 메서드 사례·오답 가르기·판단 지점 못 박힘 */
    static List<String> problems(BlueprintDto b) {
        List<String> bad = new ArrayList<>(BlueprintService.commonProblems(b));
        List<String> caseNames = b.cases().stream().map(BlueprintDto.Case::name).toList();
        if (b.flaws().stream().anyMatch(f -> !blank(f.caughtBy()) && caseNames.stream().noneMatch(n -> n != null && f.caughtBy().contains(n)))) {
            bad.add("결함의 caughtBy 는 사례(cases)의 name 중 하나여야 합니다");
        }
        for (var f : b.flaws()) {
            if (blank(f.wrongAnswer())) continue;
            boolean separated = !blank(f.separatedBy()) && caseNames.contains(f.separatedBy().strip());
            if (!separated || f.separatedBy().strip().equals(f.caughtBy() == null ? "" : f.caughtBy().strip())) {
                bad.add("결함 '" + f.what() + "' 의 오답(" + f.wrongAnswer() + ")을 정답과 가르는 사례가 필요합니다: separatedBy 는 caughtBy 와 다른 사례(cases)의 name 이어야 하고, "
                        + "그 사례는 오답 방식과 정답의 결과가 달라지는 입력과 기대 결과, 근거 문장(보이는 examples 에 있는 그대로)을 가져야 합니다");
            }
        }
        bad.addAll(addedMethodCases(b));
        bad.addAll(exampleBasisCases(b));
        for (var c : b.cases()) { // Q7 개정: 기존 메서드 예는 화면에 안 나오니 사례 근거가 될 수 없다
            var e = ExampleLineService.basisExample(b, c);
            if (e != null && !ExampleLineService.shown(b, e)) {
                bad.add("사례 " + c.name() + " 의 근거가 기존 메서드 예 '" + e.input() + " → " + e.expected() + "' 입니다. 기존 메서드 예는 요구사항에 나오지 않으니(공개 테스트에만 있음) 그 결과를 끌어낼 수 있는 기록을 사실(facts)로 두고 근거로 쓰세요");
            }
        }
        bad.addAll(pinnedJudgments(b));
        bad.addAll(flawHidden(b));
        bad.addAll(flawCasesInRequirements(b));
        // g10: 설명 자리 사실·상황이 "기존 ~ 결과를 정산 내역과 대조하는 작업" 이면 작성 단계는 사실을 그대로 넣어야 해서 고칠 수 없다(01: 작성 2번 다시 해도 같은 문장)
        if (TaskWritingService.comparesExisting(b.scenario())) bad.add("상황(scenario)이 기존 동작을 기록과 대조·검토하는 작업을 말합니다: " + b.scenario() + ". 상황은 추가할 기능과 업무 배경만 쓰세요");
        for (var f : nz(b.facts())) {
            if (BlueprintService.DESCRIPTION.equals(f.place()) && TaskWritingService.comparesExisting(f.text())) {
                bad.add("설명 자리 사실이 기존 동작을 기록과 대조·검토하는 작업을 말합니다: " + f.text() + ". 이런 문장은 결함을 가리키니 빼세요(설명은 상황과 추가할 기능만)");
            }
        }
        if (b.api().isEmpty()) bad.add("공개 API(api)가 있어야 합니다");
        for (String a : b.api()) {
            if (a != null && (a.contains("{") || a.contains("}") || a.contains(";"))) bad.add("api 항목에는 시그니처만 쓰고 본문({ ... })이나 ;는 쓰지 않습니다: " + a);
        }
        if (b.examples().size() < 2 || b.examples().stream().anyMatch(e -> blank(e.input()) || blank(e.expected()))) bad.add("입력→기대 출력 예(examples)가 2개 이상 있어야 합니다");
        // Q7: 요구사항 예시는 코드가 method·args·expected 로 표 줄을 만든다(2026-11-28)
        for (var e : b.examples()) {
            if (blank(e.method()) || e.args() == null || e.args().stream().anyMatch(a -> a == null || a.size() != 2 || blank(a.get(0)))) {
                bad.add("예 '" + e.input() + "' 에 표 칸이 없습니다: method(\"Class.method\", api 의 메서드)와 args(매개변수마다 [\"이름\", \"짧은 표시값\"], 시그니처 순서, 매개변수가 없으면 [])를 채우세요");
            }
        }
        return bad;
    }

    /** E6·M6 DEV 목록 검사: 필요한 묶음 개수·경로(src·test·NOTES.md), NOTES.md 면 notes 사실, 관련 적은 자료 꼴, 파일 수 */
    static List<String> materialProblems(BlueprintDto b, int minutes) {
        List<String> bad = new ArrayList<>();
        int[] r = BlueprintService.materialRange(false, minutes);
        List<String> needed = nz(b.neededFiles());
        BlueprintService.neededCount(bad, b, r, minutes);
        for (String n : needed) {
            if (!((n.startsWith("src/") || n.startsWith("test/")) && n.endsWith(".java")) && !n.equals(MaterialGateService.NOTES_FILE)) {
                bad.add("필요한 묶음 파일 경로는 src/…java, test/…java, NOTES.md 여야 합니다: " + n);
            }
        }
        // NOTES.md 는 place=notes 사실이 있을 때만 작성 단계가 만든다. 없으면 뒤에서 "필요한 묶음 파일이 만들어지지 않았습니다"로 실패하니(g3 04, g4 07) 여기서 미리 알린다
        if (needed.contains(MaterialGateService.NOTES_FILE) && nz(b.facts()).stream().noneMatch(f -> BlueprintService.NOTES.equals(f.place()))) {
            bad.add("neededFiles 에 NOTES.md 가 있으면 place 가 notes 인 사실이 1개 이상 있어야 합니다(없으면 NOTES.md 가 만들어지지 않습니다). 접수 메모 사실을 notes 에 두거나 NOTES.md 를 빼세요");
        }
        BlueprintService.fillerShape(bad, b, List.of(), p -> p.startsWith("src/") && p.endsWith(".java") && !p.contains(".."), "src/…java");
        // 공개 테스트·NOTES 처럼 목록에 안 적는 파일이 있을 수 있어 아래 끝을 2 넉넉히 둔다
        BlueprintService.fileCount(bad, b, needed.size() + nz(b.fillers()).size(), r[2] - 2, r[3], minutes, "필요한 묶음과 관련 적은 자료의 합");
        return bad;
    }

    /** "추가:" 메서드마다 그 메서드를 부르는 사례가 있어야 하고, 결함을 잡는 사례(caughtBy·separatedBy)는 추가 메서드를 부르지 않는다(틀이 던지는 예외로 항상 실패해 결함 검사가 공허해진다) */
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

    private static final int MIN_EXAMPLE_INPUT = 8;

    /**
     * 사례 근거가 예(examples) 문장이면 그 사례 입력은 그 예의 입력과 같아야 한다(g8 04: 근거는 도윤·allowed 예인데 사례는 지호·cachedAllowed →
     * 테스트 근거 관문 실패). 근거에 예의 입력이 그대로 들어 있을 때만 보고(정규화 비교), 예의 따옴표 값·숫자·메서드 호출이 사례 situation 에 모두 있어야 한다
     */
    static List<String> exampleBasisCases(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (var c : nz(b.cases())) {
            if (blank(c.basis()) || blank(c.situation())) continue;
            String basis = GenTextService.norm(c.basis());
            String situation = GenTextService.norm(c.situation());
            for (var e : nz(b.examples())) {
                String input = GenTextService.norm(e.input());
                if (input.length() < MIN_EXAMPLE_INPUT || !basis.contains(input)) continue;
                List<String> missing = new ArrayList<>();
                for (String l : literals(e.input())) {
                    if (!situation.contains(GenTextService.norm(l))) missing.add(l);
                }
                var m = CALL.matcher(e.input());
                while (m.find()) {
                    if (!Pattern.compile("(?<![\\w])" + Pattern.quote(m.group(1)) + "\\s*\\(").matcher(c.situation()).find()) missing.add(m.group(1) + "(");
                }
                if (!missing.isEmpty()) {
                    bad.add("사례 " + c.name() + " 의 근거는 예 '" + e.input() + "' 인데 사례 입력(situation)에 그 예의 " + missing.stream().distinct().toList()
                            + " 가 없습니다. 근거가 예면 situation 은 그 예와 같은 호출·값을 쓰고, 다른 입력을 쓰려면 그 결과를 끌어낼 수 있는 사실(facts)을 근거로 하세요");
                }
            }
        }
        return bad.stream().distinct().toList();
    }

    private static final Pattern WHERE_PATH =Pattern.compile("src/[\\w/]*?([A-Z]\\w*)\\.java");
    private static final Pattern WHERE_CALL = Pattern.compile("(?<![\\w.])([A-Z]\\w*)\\.([a-z]\\w*)");

    /** 결함 위치(flaws.where)가 가리키는 클래스 이름: 경로 src/…/X.java 의 X 와 "X.method" 의 X */
    static java.util.Set<String> flawClasses(BlueprintDto b) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (var f : nz(b.flaws())) {
            if (blank(f.where())) continue;
            var m = WHERE_PATH.matcher(f.where());
            while (m.find()) out.add(m.group(1));
            m = WHERE_CALL.matcher(f.where());
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /**
     * 결함은 설명·요구사항이 이름을 대지 않는 파일(호출되는 쪽)에 둔다(기준 5장 DEV, 2026-11-26 g6: "기존: X.method" API 목록이 결함 파일을 가리켰음).
     * 공개 API(api), 예(examples), 설명 자리 사실(place=description)에 결함 클래스 이름이 있으면 안 된다
     */
    static List<String> flawHidden(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        for (String cls : flawClasses(b)) {
            Pattern word = Pattern.compile("(?<![\\w])" + Pattern.quote(cls) + "(?![\\w])");
            for (String a : nz(b.api())) {
                if (a != null && word.matcher(a).find()) {
                    bad.add("결함 클래스 " + cls + " 가 공개 API(api)에 있습니다: " + a + ". 결함은 api 클래스가 부르는 다른 클래스(설명이 이름을 대지 않는 파일)에 두고, api 에는 호출하는 쪽 클래스만 쓰세요");
                }
            }
            for (var e : nz(b.examples())) {
                if (word.matcher((e.input() == null ? "" : e.input()) + " " + (e.expected() == null ? "" : e.expected()) + " " + ExampleLineService.line(e)).find()) {
                    bad.add("결함 클래스 " + cls + " 가 예(examples)에 있습니다: " + e.input() + ". 예는 api 클래스만 부르세요");
                }
            }
            for (var s : nz(b.exampleSetups())) {
                if (s != null && word.matcher(s.method() + " " + s.text()).find()) bad.add("결함 클래스 " + cls + " 가 예시 준비(exampleSetups)에 있습니다: " + s.text() + ". 예는 api 클래스만 부르세요");
            }
            for (var f : nz(b.facts())) {
                if (BlueprintService.DESCRIPTION.equals(f.place()) && f.text() != null && word.matcher(f.text()).find()) {
                    bad.add("결함 클래스 " + cls + " 가 설명 자리 사실에 있습니다: " + f.text() + ". 설명은 결함 파일·메서드 이름을 대지 않습니다");
                }
            }
        }
        return bad.stream().distinct().toList();
    }

    private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(");

    /**
     * 결함 사례(caughtBy·separatedBy)는 요구사항 예(examples)에 나오는 메서드를 하나 이상 부른다(g9 01: 요구사항에 없는 기존 eligible() 만 채점해 M1 이 불분명).
     * 사례 글에 메서드 호출이 없으면(서술형) 판단하지 않는다
     */
    static List<String> flawCasesInRequirements(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        java.util.Set<String> exampleCalls = new java.util.HashSet<>();
        for (var e : nz(b.examples())) {
            if (e.input() == null) continue;
            var m = CALL.matcher(e.input());
            while (m.find()) exampleCalls.add(m.group(1));
        }
        java.util.Set<String> flawCases = new java.util.LinkedHashSet<>();
        for (var f : nz(b.flaws())) {
            if (!blank(f.caughtBy())) flawCases.add(f.caughtBy().strip());
            if (!blank(f.separatedBy())) flawCases.add(f.separatedBy().strip());
        }
        for (var c : nz(b.cases())) {
            if (c.name() == null || !flawCases.contains(c.name().strip()) || blank(c.situation())) continue;
            java.util.Set<String> calls = new java.util.LinkedHashSet<>();
            var m = CALL.matcher(c.situation());
            while (m.find()) calls.add(m.group(1));
            if (!calls.isEmpty() && calls.stream().noneMatch(exampleCalls::contains)) {
                bad.add("결함 사례 " + c.name() + " 가 부르는 메서드 " + calls + " 가 요구사항 예(examples)에 없습니다. 결함은 요구사항이 다루는 메서드를 거쳐 드러나야 하니, 예에 나오는 메서드를 부르게 하거나 그 메서드의 예를 더하세요");
            }
        }
        return bad;
    }

    private static final Pattern LITERAL = Pattern.compile("\"[^\"]*\"|\\d+(?:[.,]\\d+)*");

    /** 입력 글 속 따옴표 값과 숫자("GRID", 1.25 …) */
    private static List<String> literals(String input) {
        List<String> out = new ArrayList<>();
        var m = LITERAL.matcher(input);
        while (m.find()) out.add(m.group().replace("\"", ""));
        return out;
    }

    /** 판단 지점의 선택지 문장이 사례의 근거나 예와 같으면 그 선택으로 결과가 정해져 판단 지점이 선택이 아니게 된다(M4). 판단 지점 문항(judgment)은 제외 */
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
}
