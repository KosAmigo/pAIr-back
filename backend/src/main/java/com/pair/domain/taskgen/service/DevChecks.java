package com.pair.domain.taskgen.service;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.CodeRunner.RunResult;

/** 개발 과제 검사 6개 (설계 6장). 실행은 CodeRunner 인터페이스로만 한다 */
final class DevChecks {

    static final String FORMAT = "형식";
    static final String NO_HINT = "버그 힌트 주석 없음";
    static final String START_PASSES = "시작 코드 + 공개 테스트 통과";
    static final String HIDDEN_CATCHES = "숨은 테스트가 버그를 잡음";
    static final String SOLVED_PASSES = "정답은 전부 통과";
    static final String TIME = "각 실행 30초 안";

    static final long TIME_LIMIT_MS = 30_000;
    private static final Pattern HINT = Pattern.compile(
            "(//|/\\*|\\*).*(버그|bug|TODO|FIXME|함정|일부러|잘못된|문제가 있)", Pattern.CASE_INSENSITIVE);

    private DevChecks() {}

    /** 이 Draft 하나를 위한 검사 목록. 실행 검사들이 같은 실행 결과를 쓰도록 DevRuns 를 함께 쥔다 */
    static List<GenCheck<DevDraft>> forDraft(CodeRunner runner, DevDraft draft) {
        DevRuns runs = new DevRuns(runner, draft);
        return List.of(
                check(FORMAT, d -> {
                    String p = DevRuns.formatProblem(d);
                    return p == null ? ok("경로와 필수 항목이 모두 맞습니다.") : fail(p);
                }),
                check(NO_HINT, d -> {
                    for (var f : d.files() == null ? List.<DevDraft.FileDraft>of() : d.files()) {
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
                    return r.allPassed() ? ok("테스트 " + r.passed() + "개 모두 통과") : fail(DevRuns.problem(r));
                }),
                check(HIDDEN_CATCHES, d -> {
                    if (!runs.formatOk()) return skipped();
                    RunResult r = runs.startHidden();
                    int total = r.passed() + r.failed();
                    if (r.compiled() && r.failed() > 0) return ok(total + "개 중 " + r.failed() + "개 실패");
                    return fail(r.compiled() ? "숨은 테스트가 시작 코드에서 모두 통과해 버그를 잡지 못합니다." : DevRuns.problem(r));
                }),
                check(SOLVED_PASSES, d -> {
                    if (!runs.formatOk()) return skipped();
                    RunResult r = runs.solved();
                    return r.allPassed() ? ok("테스트 " + r.passed() + "개 모두 통과") : fail(DevRuns.problem(r));
                }),
                check(TIME, d -> {
                    if (!runs.formatOk()) return skipped();
                    long a = runs.start().millis();
                    long b = runs.startHidden().millis();
                    long c = runs.solved().millis();
                    String detail = "시작 " + sec(a) + "초, 시작+숨은 " + sec(b) + "초, 정답+숨은 " + sec(c) + "초";
                    return Math.max(a, Math.max(b, c)) < TIME_LIMIT_MS ? ok(detail) : fail(detail);
                }));
    }

    private static String sec(long ms) {
        return String.format("%.1f", ms / 1000.0);
    }

    private static GenCheck<DevDraft> check(String name, Function<DevDraft, GenCheckResult> body) {
        return new GenCheck<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GenCheckResult run(DevDraft d) {
                GenCheckResult r = body.apply(d);
                return new GenCheckResult(name, r.ok(), r.detail());
            }
        };
    }

    private static GenCheckResult ok(String detail) {
        return new GenCheckResult("", true, detail);
    }

    private static GenCheckResult fail(String detail) {
        return new GenCheckResult("", false, detail);
    }

    private static GenCheckResult skipped() {
        return fail("형식이 맞지 않아 실행하지 않았습니다.");
    }
}
