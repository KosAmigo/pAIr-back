package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.CodeRunner.RunResult;

/** 개발 과제 검사 15개 (설계 6장 + 검수 결과로 더한 7개 + E2·E3 검사 2개). 실행은 CodeRunner 인터페이스로만 한다 */
final class DevCheckService {

    // 기준 문서(pAIr-docs 설계/좋은_과제_기준.md, 2026-11-24 개정) 번호 대응.
    // 참고 지표(AI 단독 풀이)는 합격·불합격이 아니라 SoloSolveService 가 afterChecks 로 기록만 한다.
    static final String FORMAT = "형식"; // M1 전제
    static final String NO_HINT = "버그 힌트 주석 없음"; // M5 (결함 위치 주석 금지)
    static final String START_PASSES = "시작 코드 + 공개 테스트 통과"; // 5장 DEV, E4 (시작 코드는 컴파일되고 공개 테스트를 통과)
    static final String HIDDEN_CATCHES = "숨은 테스트가 버그를 잡음"; // E4, M3 (숨은 테스트는 시작 코드에서 실패: 아직 고치지·만들지 않은 부분)
    static final String SOLVED_PASSES = "정답은 전부 통과"; // M1 (정답으로 풀 수 있다)
    static final String TIME = "각 실행 30초 안"; // M2 (맞는 풀이를 시간 초과로 떨어뜨리지 않음)
    static final String WRONG_FIXES = "숨은 결함을 놓친 풀이를 숨은 테스트가 걸러냄"; // M3 (옛 이름: 그럴듯한 오답을 숨은 테스트가 걸러냄)
    static final String AMOUNT = "분량 대비 시간"; // Q1 (6장 표)
    static final String FOLLOW_UP = "후속 질문이 과제에 맞음"; // E5, M5
    static final String ALT_SOLUTIONS = "다른 방식의 올바른 풀이도 통과"; // M2
    static final String JUDGMENT = "판단 지점과 근거가 자료에 있음"; // E2, M4(존재·근거 위치만 자동 확인, 적절성은 검수)
    static final String HIDDEN_FLAW = "숨은 결함을 숨은 테스트가 잡음"; // E3, M3
    static final String FAKE_IMPL = "추가한 메서드를 상수로 바꾸면 숨은 테스트가 실패함"; // M3: 구현을 검증하는 테스트가 있다

    static final long TIME_LIMIT_MS = 30_000;

    /**
     * 제한 시간별 정답 변경 줄 수(추가+삭제) 최소치. 리뷰 결과로 정한 대략치이며 정확한 기준은 아니다.
     * 이보다 적으면 경고(warn)만 하고 다시 생성하지는 않는다.
     */
    static final Map<Integer, Integer> MIN_CHANGED_LINES = Map.of(20, 2, 40, 6, 60, 12);

    private static final int MIN_FOLLOW_UP_TEST_NAME = 4;
    private static final Pattern HINT = Pattern.compile(
            "(//|/\\*|\\*).*(버그|bug|TODO|FIXME|함정|일부러|잘못된|문제가 있)", Pattern.CASE_INSENSITIVE);

    /**
     * 접수 메모(NOTES.md·INCIDENT.md)에서 해법을 알려 주는 말. 현상·재현 값만 적어야 하는 글이라 "~해야 합니다", "~하도록" 같은 말이 없어야 한다.
     * 넓게 잡으면 오탐이 늘어 말끝 형태까지 맞춘다(경고만 하므로 약간의 오탐은 허용)
     */
    static final Pattern NOTES_HINT = Pattern.compile(
            "해야\\s*(합니다|한다|함|해요)|하도록|도록\\s*(고[치친쳐]|수정|바[꾸꾼꿔]|변경)|고쳐야|고치면|고친다면|수정해야|수정하면|바꿔야|바꾸면|한\\s*번에\\s*확정|확정해야|일치시켜야"
                    + "|[가-힣]+(되|히|리|기)지\\s*않(는다|습니다|음)"
                    + "|원인\\s*[:：]|(과|와)[^.\\n]{1,40}(은|는|이|가)\\s*(서로\\s*)?(다릅니다|다르다)"); // M5: "실적으로 잡히지 않는다" 꼴 규칙 문장, "원인:" 표지, "A와 B는 다르다" 차이 문장
    /** 요구사항에서 해법·계산 결합 방식을 말로 푼 것. 입력→기대 출력, 불변식만 있어야 한다 */
    static final Pattern REQ_HINT = Pattern.compile(
            "하도록|도록\\s*(고[치친쳐]|수정|바[꾸꾼꿔]|변경)|고쳐야|고치면|수정해야|바꿔야|바꾸면|곱해|곱한|곱하여|곱하고|(나눈|나누어|뺀|더한|합친)\\s*(뒤|후|값에|금액에)"
                    + "|내림(?!차순)|올림|버림|\\d+분의\\s*\\d+|가장\\s*(큰|작은)\\s*정수|한\\s*번에\\s*확정"
                    + "|(범위|한도|상한|하한)[^.\\n]{0,20}(넘|벗어|초과)[^.\\n]{0,40}(예외|Exception|거절|오류)[^.\\n]{0,10}(발생|던진|난다)"); // M5: 결함을 피하는 결과를 규칙 문장으로 말함
    private static final int MAX_CLUES = 5;
    private static final int CLUE_CHARS = 110;
    /** 풀이가 바꾼 줄에서 문장과 겹침을 볼 때 뺄 흔한 말 */
    private static final java.util.Set<String> COMMON_TOKENS = java.util.Set.of("return", "public", "private", "protected", "static", "final",
            "void", "this", "else", "class", "String", "boolean", "long", "int", "new", "null", "true", "false", "synchronized", "throw",
            "throws", "import", "package", "List", "Map", "Math");

    private DevCheckService() {}

    /** 이 Draft 하나를 위한 검사 목록 (60분 기준, 폼 없이) */
    static List<GenCheckService<DevDraftDto>> forDraft(CodeRunner runner, DevDraftDto draft) {
        return forDraft(runner, draft, null);
    }

    /**
     * 이 Draft 하나를 위한 검사 목록. 실행 검사들이 같은 실행 결과를 쓰도록 DevRunService 를 함께 쥔다.
     * form: 함정 문장에서 핵심어를 더 뽑고 제한 시간(분량 검사)을 알기 위해 쓴다. null 이면 60분, 함정 문장 없음.
     */
    static List<GenCheckService<DevDraftDto>> forDraft(CodeRunner runner, DevDraftDto draft, TaskGenRequest form) {
        DevRunService runs = new DevRunService(runner, draft);
        return List.of(
                check(FORMAT, d -> {
                    String p = DevRunService.formatProblem(d);
                    return p == null ? ok("경로와 필수 항목이 모두 맞습니다.") : formatFail(p, d);
                }),
                check(NO_HINT, d -> {
                    for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
                        if (f != null && f.path() != null && f.path().startsWith("src/") && f.content() != null) {
                            var m = HINT.matcher(f.content());
                            if (m.find()) return fail(f.path() + " 에 버그 힌트 주석이 있습니다: " + m.group().strip());
                        }
                    }
                    return ok("src 주석에 힌트가 없습니다.");
                }),
                check(START_PASSES, d -> {
                    if (!runs.formatOk()) return skipped();
                    RunResult r = runs.start();
                    return r.allPassed() ? ok("테스트 " + r.passed() + "개 모두 통과") : fail(DevRunService.problem(r));
                }),
                check(HIDDEN_CATCHES, d -> {
                    if (!runs.formatOk()) return skipped();
                    RunResult r = runs.startHidden();
                    int total = r.passed() + r.failed();
                    if (r.compiled() && r.failed() > 0) return ok(total + "개 중 " + r.failed() + "개 실패");
                    return fail(r.compiled() ? "숨은 테스트가 시작 코드에서 모두 통과해 버그를 잡지 못합니다." : DevRunService.problem(r));
                }),
                check(SOLVED_PASSES, d -> {
                    if (!runs.formatOk()) return skipped();
                    RunResult r = runs.solved();
                    return r.allPassed() ? ok("테스트 " + r.passed() + "개 모두 통과") : fail(DevRunService.problem(r));
                }),
                checkOwnLevel(WRONG_FIXES, d -> { // 공개 테스트도 깨는 약한 오답은 경고(warn)로 돌려준다
                    if (!runs.formatOk()) return skipped();
                    String p = DevRunService.wrongFixProblem(d);
                    if (p != null) return fail(p);
                    return wrongFixes(d, runs.wrongRuns());
                }),
                check(TIME, d -> {
                    if (!runs.formatOk()) return skipped();
                    long a = runs.start().millis();
                    long b = runs.startHidden().millis();
                    long c = runs.solved().millis();
                    long w = runs.wrongRuns().stream().mapToLong(x -> x.result().millis()).max().orElse(0);
                    String detail = "시작 " + sec(a) + "초, 시작+숨은 " + sec(b) + "초, 정답+숨은 " + sec(c) + "초"
                            + (runs.wrongRuns().isEmpty() ? "" : ", 오답+숨은 최대 " + sec(w) + "초");
                    return Math.max(Math.max(a, b), Math.max(c, w)) < TIME_LIMIT_MS ? ok(detail) : fail(detail);
                }),
                warn(AMOUNT, d -> amount(d, form == null ? 60 : form.minutes())),
                check(FOLLOW_UP, DevCheckService::followUp),
                // 다른 방식의 올바른 풀이가 없으면 warn(옛 형식 호환), 있는데 떨어지면 fail 이라 결과마다 등급이 다르다
                checkOwnLevel(ALT_SOLUTIONS, d -> {
                    if (!runs.formatOk()) return skipped();
                    return altSolutions(d, runs);
                }),
                checkOwnLevel(JUDGMENT, d -> GenTextService.judgmentPoints(d.judgmentPoints(), visibleCorpus(d), true)),
                check(FAKE_IMPL, d -> {
                    if (!runs.formatOk()) return skipped();
                    List<String> bad = FakeImplService.problems(runner, d);
                    return bad.isEmpty() ? ok("추가한 메서드를 상수 반환으로 바꾸면 숨은 테스트가 실패합니다(확인할 수 있는 메서드만).")
                            : fail(String.join(" / ", bad) + ". 그 메서드의 결과가 다른(반대 값) 사례가 설계도에 있어야 합니다.");
                }),
                checkOwnLevel(HIDDEN_FLAW, d -> {
                    if (!runs.formatOk()) return skipped();
                    return hiddenFlaws(d, runs);
                }));
    }

    // ── 다른 풀이, 해법 문장 ─────────────────────────────

    /**
     * 정답과 구조가 다른 올바른 풀이 1~2개가 숨은+공개 테스트를 모두 통과해야 한다. 하나라도 떨어지면 fail
     * (숨은 테스트가 특정 구현 구조를 전제해 올바른 풀이를 떨어뜨리는 경우). 없으면 warn.
     */
    private static GenCheckResultDto altSolutions(DevDraftDto d, DevRunService runs) {
        List<DevDraftDto.AltSolution> alts = d.altSolutions() == null ? List.of() : d.altSolutions();
        if (alts.isEmpty()) {
            return warnResult("정답과 다른 방식의 올바른 풀이(altSolutions)가 없어 숨은 테스트가 올바른 풀이를 떨어뜨리는지 확인하지 못했습니다.");
        }
        String p = DevRunService.altSolutionProblem(d);
        if (p != null) return fail(p);
        Map<String, String> solution = DevRunService.toMap(d.solution());
        boolean same = alts.stream().allMatch(a -> {
            Map<String, String> m = DevRunService.toMap(a.files());
            return m.size() == solution.size() && m.entrySet().stream()
                    .allMatch(e -> solution.containsKey(e.getKey()) && noSpace(solution.get(e.getKey())).equals(noSpace(e.getValue())));
        });
        if (same) return warnResult("다른 풀이가 정답과 같아 확인이 되지 않습니다. 락 위치·자료구조·메서드 분리 등 구조가 다른 풀이를 쓰세요.");
        List<String> bad = new ArrayList<>();
        for (var run : runs.altRuns()) {
            var r = run.result();
            String label = "다른 풀이 " + run.number() + (run.alt().note() == null || run.alt().note().isBlank() ? "" : " (" + shortText(run.alt().note()) + ")");
            if (!r.compiled()) {
                bad.add(label + " 이 컴파일되지 않습니다: " + DevRunService.problem(r));
            } else if (r.failed() > 0) {
                List<String> names = r.tests().stream().filter(t -> !t.passed()).limit(3).map(CodeRunner.TestCase::name).toList();
                bad.add("테스트 " + names + " 가 올바른 풀이인 " + label + " 을 떨어뜨렸습니다(" + r.failed() + "개 실패)");
            }
        }
        if (bad.isEmpty()) return ok("다른 방식의 올바른 풀이 " + runs.altRuns().size() + "개 모두 숨은·공개 테스트를 통과합니다.");
        return fail(String.join(" / ", bad) + ". 숨은 테스트가 특정 구현 구조(어떤 메서드를 락 안/밖에서 부르는지 등)를 전제하면 안 됩니다. "
                + "대기 장치(CyclicBarrier·latch)는 타임아웃이 나도 실패시키지 말고 그냥 진행하게 하고, 단언은 결과(재고 음수 여부, 승인 개수 등)만 하세요.");
    }

    private static String noSpace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 글을 문장(또는 줄) 단위로 나눈다. 비어 있는 조각은 뺀다 */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("(?<=[.!?。])\\s+|\\R")) {
            String t = part.strip().replaceFirst("^[-*#>\\s]+", "");
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /**
     * 풀이(AI 단독 풀이 등)가 단서로 썼을 만한 문장. 요구사항·메모·summary 에서 (1) 해법을 말하는 문장이거나
     * (2) 풀이가 바꾼 줄(fixSummary)의 식별자(4글자 이상)가 들어 있는 문장을 고른다. AI 호출 없이 정규식과 단순 비교만 한다.
     * 각 문장은 [출처] 를 붙여 짧게 줄이고 최대 5개
     */
    static List<String> clueSentences(DevDraftDto d, String fixSummary) {
        List<String> tokens = new ArrayList<>();
        var m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}").matcher(fixSummary == null ? "" : fixSummary);
        while (m.find()) if (!COMMON_TOKENS.contains(m.group()) && !tokens.contains(m.group())) tokens.add(m.group());
        List<String> out = new ArrayList<>();
        for (String r : d.requirements() == null ? List.<String>of() : d.requirements()) {
            for (String sentence : sentences(r)) addClue(out, "요구사항", sentence, REQ_HINT, tokens);
        }
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f == null || f.path() == null || f.content() == null) continue;
            if (!f.path().equals("NOTES.md") && !f.path().equals("INCIDENT.md")) continue;
            for (String sentence : sentences(f.content())) addClue(out, f.path(), sentence, NOTES_HINT, tokens);
        }
        for (String sentence : sentences(d.summary())) addClue(out, "summary", sentence, REQ_HINT, tokens);
        return out.size() > MAX_CLUES ? out.subList(0, MAX_CLUES) : out;
    }

    private static void addClue(List<String> out, String source, String sentence, Pattern hint, List<String> tokens) {
        String lower = sentence.toLowerCase();
        boolean hit = hint.matcher(sentence).find() || tokens.stream().anyMatch(t -> lower.contains(t.toLowerCase()));
        if (hit) out.add("[" + source + "] " + clip(sentence, CLUE_CHARS));
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── E2·E3 ─────────────────────────────

    /** 응시자에게 보이는 글(설명, 요구사항, src·공개 테스트 코드와 주석, 메모·로그). 숨은 테스트·정답은 뺀다 */
    static String visibleCorpus(DevDraftDto d) {
        StringBuilder sb = new StringBuilder(join(d.description())).append("\n").append(join(d.requirements())).append("\n");
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f != null && f.content() != null) sb.append(f.content()).append("\n");
        }
        return sb.toString();
    }

    /**
     * E3: 숨은 결함이 1개 이상 선언돼 있고, 결함마다 설명·대조 방법이 있으며 caughtBy 가 실제 숨은 테스트 메서드여야 하고,
     * 그 테스트가 시작 코드나 숨은 결함을 놓친 풀이(wrongFixes) 중 하나에서 실제로 실패해야 한다(결함을 잡는다).
     * 필드가 없으면(옛 형식, null) warn
     */
    private static GenCheckResultDto hiddenFlaws(DevDraftDto d, DevRunService runs) {
        if (d.hiddenFlaws() == null) return warnResult("hiddenFlaws 가 없는 옛 형식이라 숨은 결함을 잡는 테스트를 확인하지 못했어요");
        if (d.hiddenFlaws().isEmpty()) return fail("숨은 결함(hiddenFlaws)이 1개 이상 있어야 합니다(AI 기본 답이 놓치기 쉬운 곳).");
        List<String> hiddenNames = GenTextService.testNames(d.hiddenTests());
        List<String> failedNames = new ArrayList<>();
        if (runs.startHidden().compiled()) runs.startHidden().tests().stream().filter(t -> !t.passed()).forEach(t -> failedNames.add(t.name()));
        for (var w : runs.wrongRuns()) w.result().tests().stream().filter(t -> !t.passed()).forEach(t -> failedNames.add(t.name()));
        List<String> bad = new ArrayList<>();
        int i = 0;
        for (var f : d.hiddenFlaws()) {
            i++;
            if (f == null || isBlankText(f.description())) { bad.add("숨은 결함 " + i + "에 설명(description)이 없습니다"); continue; }
            if (isBlankText(f.verifyWith())) { bad.add("숨은 결함 " + i + "에 대조 방법(verifyWith)이 없습니다"); continue; }
            String test = f.caughtBy() == null ? "" : f.caughtBy().strip().replaceAll("\\(\\)$", "");
            String match = hiddenNames.stream().filter(n -> n.equals(test)).findFirst().orElse(null);
            if (match == null) {
                bad.add("숨은 결함 " + i + "을 잡는 숨은 테스트 '" + test + "' 가 숨은 테스트 파일에 없습니다(caughtBy 는 메서드 이름 그대로)");
            } else if (failedNames.stream().noneMatch(n -> n.endsWith("." + match) || n.endsWith("." + match + "()") || n.equals(match))) {
                bad.add("숨은 결함 " + i + "을 잡는 테스트 '" + match + "' 가 시작 코드와 숨은 결함을 놓친 풀이 어디에서도 실패하지 않아 결함을 잡지 못합니다");
            }
        }
        return bad.isEmpty() ? ok("숨은 결함 " + d.hiddenFlaws().size() + "개를 잡는 숨은 테스트가 실제로 실패합니다.") : fail(String.join(" / ", bad));
    }

    private static boolean isBlankText(String s) {
        return s == null || s.isBlank();
    }

    // ── 새 검사들 ─────────────────────────────

    private static String join(List<String> l) {
        return l == null ? "" : String.join("\n", l);
    }

    private static boolean isCode(DevDraftDto.FileDraft f) {
        return f.path().endsWith(".java") && f.content() != null;
    }

    /** 오답마다 숨은 테스트가 1개 이상 실패해야 통과 */
    private static GenCheckResultDto wrongFixes(DevDraftDto d, List<DevRunService.WrongRun> runs) {
        List<String> hiddenClasses = new ArrayList<>();
        for (var f : d.hiddenTests()) hiddenClasses.add(f.path().substring(f.path().lastIndexOf('/') + 1).replaceAll("\\.java$", ""));
        List<String> bad = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        for (var w : runs) {
            var r = w.result();
            String label = "오답 " + w.number() + " (" + shortText(w.fix().reason()) + ")";
            if (!r.compiled()) {
                bad.add(label + " 이 컴파일되지 않습니다: " + DevRunService.problem(r));
            } else if (r.failed() == 0) {
                bad.add(label + " 이 숨은 테스트를 모두 통과했습니다. 이 오답을 떨어뜨리는 숨은 테스트를 더하세요");
            } else if (r.tests().stream().noneMatch(t -> !t.passed() && hiddenClasses.stream().anyMatch(c -> t.name().contains(c)))) {
                bad.add(label + " 이 공개 테스트에서만 실패합니다. 공개 테스트는 통과하고 숨은 테스트에서만 실패하는 오답이어야 합니다");
            } else {
                // 숨은 테스트에서 떨어지지만 공개 테스트도 깨면 응시자가 바로 알아채는 약한 오답이다(g13 02 판정). 새 검사라 경고로만 알린다
                List<String> publicFails = r.tests().stream().filter(t -> !t.passed() && hiddenClasses.stream().noneMatch(c -> t.name().contains(c))).map(CodeRunner.TestCase::name).toList();
                if (!publicFails.isEmpty()) weak.add(label + " 이 공개 테스트도 깹니다(" + String.join(", ", publicFails) + "). 공개 테스트는 통과하는 오답이어야 결함을 가리는 그럴듯한 풀이가 됩니다");
            }
        }
        if (!bad.isEmpty()) return fail(String.join(" / ", bad));
        if (!weak.isEmpty()) return warnResult(String.join(" / ", weak));
        return ok("오답 " + runs.size() + "개 모두 숨은 테스트에서 실패합니다.");
    }

    /** 정답과 시작 코드가 바뀐 줄 수(추가+삭제)가 제한 시간별 최소치보다 적으면 경고 */
    private static GenCheckResultDto amount(DevDraftDto d, int minutes) {
        if (DevRunService.formatProblem(d) != null) return ok("형식이 맞지 않아 확인하지 않았습니다.");
        int changed = 0;
        Map<String, String> start = DevRunService.toMap(d.files());
        for (var f : d.solution()) changed += changedLines(start.get(f.path()), f.content());
        int min = MIN_CHANGED_LINES.getOrDefault(minutes, MIN_CHANGED_LINES.get(60));
        return changed >= min ? ok("정답 변경 " + changed + "줄 (" + minutes + "분 기준 " + min + "줄 이상)")
                : fail("정답 변경 " + changed + "줄, " + minutes + "분 과제치고 적음. 난이도·시간을 낮추거나 자료를 보강하세요");
    }

    /**
     * 두 파일에서 바뀐 줄 수(추가+삭제). 공백·빈 줄은 무시하고, 줄을 옮기기만 한 것은 세지 않는다
     * (정리한 줄 모음을 비교한다). 한 줄을 고치면 삭제 1 + 추가 1 = 2줄
     */
    static int changedLines(String before, String after) {
        Map<String, Integer> diff = lineDiff(before, after, new LinkedHashMap<>());
        int n = 0;
        for (int v : diff.values()) n += Math.abs(v);
        return n;
    }

    /** 바뀐 줄을 "+추가 -삭제" 모양의 짧은 글로(미리 정리한 줄 기준). 바뀐 게 없으면 빈 글 */
    static String changeSummary(String before, String after) {
        List<String> add = new ArrayList<>(), del = new ArrayList<>();
        Map<String, String> shown = new LinkedHashMap<>();
        lineDiff(before, after, shown).forEach((key, v) -> {
            for (int i = 0; i < Math.abs(v); i++) (v > 0 ? add : del).add(shown.get(key));
        });
        StringBuilder sb = new StringBuilder();
        for (String l : del) sb.append("-").append(l.length() > 80 ? l.substring(0, 80) : l).append(' ');
        for (String l : add) sb.append("+").append(l.length() > 80 ? l.substring(0, 80) : l).append(' ');
        return sb.toString().strip();
    }

    /** 줄별 (후 - 전) 개수. 모든 공백을 지운 줄로 센다 */
    private static Map<String, Integer> lineDiff(String before, String after, Map<String, String> shown) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String l : lines(before)) {
            String k = l.replaceAll("\\s+", "");
            shown.putIfAbsent(k, l);
            m.merge(k, -1, Integer::sum);
        }
        for (String l : lines(after)) {
            String k = l.replaceAll("\\s+", "");
            shown.putIfAbsent(k, l);
            m.merge(k, 1, Integer::sum);
        }
        m.values().removeIf(v -> v == 0);
        return m;
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String l : s.split("\\R")) if (!l.isBlank()) out.add(l.strip());
        }
        return out;
    }

    /** E5: 3번째 후속 질문(과제 맞춤)이 있어야 하고, 함정 핵심어나 숨은 테스트 이름이 들어 있으면 안 된다 */
    private static GenCheckResultDto followUp(DevDraftDto d) {
        String q = d.taskFollowUp();
        if (q == null || q.isBlank()) return fail("이 과제의 함정에 맞춘 후속 질문(taskFollowUp)이 비어 있습니다.");
        List<String> all = new ArrayList<>(GenTextService.hits(d.trapKeywords(), q));
        List<String> names = GenTextService.testNames(d.hiddenTests()).stream().filter(n -> n.length() >= MIN_FOLLOW_UP_TEST_NAME).toList();
        all.addAll(GenTextService.hits(names, q));
        if (!all.isEmpty()) {
            return fail("후속 질문에 정답을 드러내는 말이 있습니다: " + all.stream().map(h -> "'" + h + "'").toList()
                    + ". 숨은 사례 이름과 핵심어 없이 검증 방법만 묻도록 고치세요.");
        }
        return ok("과제 맞춤 후속 질문이 있고 핵심어·테스트 이름이 없습니다.");
    }

    private static String shortText(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String sec(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }

    private static GenCheckService<DevDraftDto> check(String name, Function<DevDraftDto, GenCheckResultDto> body) {
        return check(name, GenCheckResultDto.FAIL, body);
    }

    /** 실패해도 다시 생성하지 않고 검수자에게 알리기만 하는 검사 */
    private static GenCheckService<DevDraftDto> warn(String name, Function<DevDraftDto, GenCheckResultDto> body) {
        return check(name, GenCheckResultDto.WARN, body);
    }

    /** 결과마다 등급이 다른 검사(body 가 돌려준 결과의 level 을 그대로 쓴다). 예외로 실패하면 fail */
    private static GenCheckService<DevDraftDto> checkOwnLevel(String name, Function<DevDraftDto, GenCheckResultDto> body) {
        return new GenCheckService<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GenCheckResultDto run(DevDraftDto d) {
                GenCheckResultDto r = body.apply(d);
                return new GenCheckResultDto(name, r.ok(), r.detail(), r.level(), r.stages());
            }
        };
    }

    private static GenCheckService<DevDraftDto> check(String name, String level, Function<DevDraftDto, GenCheckResultDto> body) {
        return new GenCheckService<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String level() {
                return level;
            }

            @Override
            public GenCheckResultDto run(DevDraftDto d) {
                GenCheckResultDto r = body.apply(d);
                return new GenCheckResultDto(name, r.ok(), r.detail(), level, r.stages());
            }
        };
    }

    /** 형식 실패에 다시 돌릴 단계를 붙인다: 정답 쪽 항목 → 정답 쪽, 설명·요구사항·후속 → 작성, 시작 코드·테스트·코드 경로 → 시작 코드, 그 밖의 경로 → 정답 쪽. 안 맞으면 비움(설계도부터) */
    static GenCheckResultDto formatFail(String problem, DevDraftDto draft) {
        String d = problem == null ? "" : problem;
        GenCheckResultDto r = fail(d);
        List<String> codePaths = draft == null || draft.files() == null ? List.of() : draft.files().stream().filter(f -> f != null && f.path() != null).map(DevDraftDto.FileDraft::path).toList();
        if (d.contains("정답 설명") || d.contains("숨은 테스트") || d.contains("정답 코드")) return r.withStages("ANSWER");
        if (d.contains("설명(description)") || d.contains("요구사항(requirements)") || d.contains("후속")) return r.withStages("WRITING");
        if (d.contains("시작 코드") || d.contains("공개 테스트") || d.contains("같은 경로") || codePaths.stream().anyMatch(d::contains)) return r.withStages("CODE");
        if (d.contains("경로")) return r.withStages("ANSWER");
        return r;
    }

    private static GenCheckResultDto ok(String detail) {
        return new GenCheckResultDto("", true, detail);
    }

    /** 통과하지 못했지만 다시 생성하지는 않고 알리기만 하는 결과 */
    private static GenCheckResultDto warnResult(String detail) {
        return new GenCheckResultDto("", false, detail, GenCheckResultDto.WARN);
    }

    private static GenCheckResultDto fail(String detail) {
        return new GenCheckResultDto("", false, detail);
    }

    private static GenCheckResultDto skipped() {
        return fail("형식이 맞지 않아 실행하지 않았습니다.");
    }
}
