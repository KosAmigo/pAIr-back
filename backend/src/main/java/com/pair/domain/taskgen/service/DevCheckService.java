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

/** 개발 과제 검사 11개 (설계 6장 + 검수 결과로 더한 5개). 실행은 CodeRunner 인터페이스로만 한다 */
final class DevCheckService {

    static final String FORMAT = "형식";
    static final String NO_HINT = "버그 힌트 주석 없음";
    static final String START_PASSES = "시작 코드 + 공개 테스트 통과";
    static final String HIDDEN_CATCHES = "숨은 테스트가 버그를 잡음";
    static final String SOLVED_PASSES = "정답은 전부 통과";
    static final String TIME = "각 실행 30초 안";
    static final String TRAP_WORDS = "함정 핵심어 노출 없음";
    static final String WRONG_FIXES = "그럴듯한 오답을 숨은 테스트가 걸러냄";
    static final String AMOUNT = "분량 대비 시간";
    static final String MATERIALS = "과제 종류별 필수 자료";
    static final String FOLLOW_UP = "후속 질문이 과제에 맞음";

    static final long TIME_LIMIT_MS = 30_000;

    /**
     * 제한 시간별 정답 변경 줄 수(추가+삭제) 최소치. 리뷰 결과로 정한 대략치이며 정확한 기준은 아니다.
     * 이보다 적으면 경고(warn)만 하고 다시 생성하지는 않는다.
     */
    static final Map<Integer, Integer> MIN_CHANGED_LINES = Map.of(20, 2, 40, 6, 60, 12);

    private static final int MIN_FOLLOW_UP_TEST_NAME = 4;
    private static final int MIN_MATERIAL_CHARS = 200;
    private static final Pattern HINT = Pattern.compile(
            "(//|/\\*|\\*).*(버그|bug|TODO|FIXME|함정|일부러|잘못된|문제가 있)", Pattern.CASE_INSENSITIVE);

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
                    return p == null ? ok("경로와 필수 항목이 모두 맞습니다.") : fail(p);
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
                check(TRAP_WORDS, d -> trapWords(d, form)),
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
                check(WRONG_FIXES, d -> {
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
                check(MATERIALS, DevCheckService::materials),
                check(FOLLOW_UP, DevCheckService::followUp));
    }

    // ── 새 검사들 ─────────────────────────────

    private static String join(List<String> l) {
        return l == null ? "" : String.join("\n", l);
    }

    private static boolean isCode(DevDraftDto.FileDraft f) {
        return f.path().endsWith(".java") && f.content() != null;
    }

    /** 함정 핵심어가 응시자에게 보이는 곳(설명, 요구사항, 보이는 파일 경로, 보이는 코드 주석, 보이는 메모·로그)에 있으면 실패 */
    private static GenCheckResultDto trapWords(DevDraftDto d, TaskGenRequest form) {
        List<String> keywords = GenTextService.clean(d.trapKeywords());
        if (keywords.size() < 2) return fail("함정 핵심어(trapKeywords)가 2~5개 있어야 합니다 (지금 " + keywords.size() + "개).");
        List<String> words = new ArrayList<>(keywords);
        if (form != null) {
            for (String w : GenTextService.formWords(form.trap(), form.topic())) if (!words.contains(w)) words.add(w);
        }
        StringBuilder paths = new StringBuilder();
        StringBuilder comments = new StringBuilder();
        StringBuilder extras = new StringBuilder();
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f == null || f.path() == null) continue;
            paths.append(f.path()).append('\n');
            if (isCode(f)) comments.append(GenTextService.comments(f.content()));
            else if (f.content() != null) extras.append(f.content()).append('\n');
        }
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("설명", GenTextService.hits(words, join(d.description())));
        found.put("요구사항", GenTextService.hits(words, join(d.requirements())));
        found.put("보이는 파일 경로", GenTextService.hits(words, paths.toString()));
        found.put("보이는 코드 주석", GenTextService.hits(words, comments.toString()));
        // 메모·로그는 모델이 직접 정한 핵심어로만 본다(폼에서 뽑은 말은 로그에 자연스럽게 나올 수 있어 과탐이 난다)
        found.put("메모·로그", GenTextService.hits(keywords, extras.toString()));
        List<String> parts = new ArrayList<>();
        found.forEach((where, hit) -> {
            if (!hit.isEmpty()) parts.add(where + "에 " + hit.stream().map(h -> "'" + h + "'").toList());
        });
        return parts.isEmpty() ? ok("함정 핵심어 " + keywords.size() + "개가 응시자에게 보이는 곳에 없습니다.")
                : fail("응시자에게 보이는 곳에 함정 핵심어가 드러납니다: " + String.join(" / ", parts) + ". 이 말을 쓰지 말고 현상만 설명하세요.");
    }

    /** 오답마다 숨은 테스트가 1개 이상 실패해야 통과 */
    private static GenCheckResultDto wrongFixes(DevDraftDto d, List<DevRunService.WrongRun> runs) {
        List<String> hiddenClasses = new ArrayList<>();
        for (var f : d.hiddenTests()) hiddenClasses.add(f.path().substring(f.path().lastIndexOf('/') + 1).replaceAll("\\.java$", ""));
        List<String> bad = new ArrayList<>();
        for (var w : runs) {
            var r = w.result();
            String label = "오답 " + w.number() + " (" + shortText(w.fix().reason()) + ")";
            if (!r.compiled()) {
                bad.add(label + " 이 컴파일되지 않습니다: " + DevRunService.problem(r));
            } else if (r.failed() == 0) {
                bad.add(label + " 이 숨은 테스트를 모두 통과했습니다. 이 오답을 떨어뜨리는 숨은 테스트를 더하세요");
            } else if (r.tests().stream().noneMatch(t -> !t.passed() && hiddenClasses.stream().anyMatch(c -> t.name().contains(c)))) {
                bad.add(label + " 이 공개 테스트에서만 실패합니다. 공개 테스트는 통과하고 숨은 테스트에서만 실패하는 오답이어야 합니다");
            }
        }
        return bad.isEmpty() ? ok("오답 " + runs.size() + "개 모두 숨은 테스트에서 실패합니다.") : fail(String.join(" / ", bad));
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

    /** 두 파일에서 바뀐 줄 수(추가+삭제). 빈 줄과 앞뒤 공백은 무시한다. 한 줄을 고치면 삭제 1 + 추가 1 = 2줄 */
    static int changedLines(String before, String after) {
        List<String> a = lines(before), b = lines(after);
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        return a.size() + b.size() - 2 * lcs[0][0];
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) for (String l : s.split("\\R")) if (!l.isBlank()) out.add(l.strip());
        return out;
    }

    /** 장애 과제는 재현 로그나 접수 메모(공백 뺀 200자 이상)를 응시자 파일로 넣어야 한다 */
    private static GenCheckResultDto materials(DevDraftDto d) {
        if (d.category() == null || !d.category().contains("장애")) return ok("장애 과제가 아니라 필수 자료를 따로 확인하지 않습니다.");
        int chars = 0;
        int count = 0;
        for (var f : d.files() == null ? List.<DevDraftDto.FileDraft>of() : d.files()) {
            if (f == null || f.path() == null || f.content() == null) continue;
            if (f.path().startsWith("logs/") || f.path().equals("NOTES.md") || f.path().equals("INCIDENT.md")) {
                count++;
                chars += f.content().replaceAll("\\s+", "").length();
            }
        }
        return chars >= MIN_MATERIAL_CHARS ? ok("재현 자료 " + count + "개, 공백 뺀 " + chars + "자")
                : fail("장애 분석 과제인데 재현 자료가 부족합니다 (logs/ 파일이나 NOTES.md·INCIDENT.md, 공백 뺀 " + MIN_MATERIAL_CHARS
                        + "자 이상 필요, 지금 " + chars + "자). 재현 로그나 접수 메모를 응시자 파일로 넣으세요.");
    }

    /** 3번째 후속 질문(과제 맞춤)이 있어야 하고, 함정 핵심어나 숨은 테스트 이름이 들어 있으면 안 된다 */
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
                return new GenCheckResultDto(name, r.ok(), r.detail(), level);
            }
        };
    }

    private static GenCheckResultDto ok(String detail) {
        return new GenCheckResultDto("", true, detail);
    }

    private static GenCheckResultDto fail(String detail) {
        return new GenCheckResultDto("", false, detail);
    }

    private static GenCheckResultDto skipped() {
        return fail("형식이 맞지 않아 실행하지 않았습니다.");
    }
}
