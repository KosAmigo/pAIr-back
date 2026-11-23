package com.pair.domain.taskgen.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pair.infra.sandbox.CodeRunner;
import com.pair.infra.sandbox.CodeRunner.RunResult;

/**
 * 한 Draft 에 대한 세 번의 실행(시작 코드, 시작 코드+숨은 테스트, 정답+숨은 테스트).
 * 여러 검사가 같은 결과를 쓰므로 처음 필요할 때 한 번만 돌리고 기억해 둔다.
 * 경로가 형식에 맞지 않으면(../ 등) 실행하지 않는다.
 */
final class DevRuns {

    static final Pattern PATH = Pattern.compile("(src|test)/[\\w/]+\\.java");

    private final CodeRunner runner;
    private final DevDraft draft;
    private RunResult start;
    private RunResult startHidden;
    private RunResult solved;

    DevRuns(CodeRunner runner, DevDraft draft) {
        this.runner = runner;
        this.draft = draft;
    }

    /** 형식 검사 규칙. 문제가 없으면 null. 실행해도 안전한지(경로가 폴더 밖으로 나가지 않는지)도 겸한다 */
    static String formatProblem(DevDraft d) {
        if (d == null) return "과제가 비어 있습니다.";
        if (isBlank(d.title())) return "제목이 비어 있습니다.";
        if (isBlank(d.answer())) return "정답 설명(answer)이 비어 있습니다.";
        if (d.description() == null || d.description().isEmpty()) return "설명(description)이 비어 있습니다.";
        if (d.requirements() == null || d.requirements().isEmpty()) return "요구사항(requirements)이 비어 있습니다.";
        List<DevDraft.FileDraft> files = nz(d.files());
        List<DevDraft.FileDraft> hidden = nz(d.hiddenTests());
        List<DevDraft.FileDraft> sol = nz(d.solution());
        for (var f : concat(files, hidden, sol)) {
            if (f == null || f.path() == null || f.content() == null) return "경로나 내용이 빈 파일이 있습니다.";
            if (!PATH.matcher(f.path()).matches() || f.path().contains("//")) {
                return "파일 경로 형식이 맞지 않습니다: " + f.path() + " (src/…java 또는 test/…java)";
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (var f : concat(files, hidden)) seen.merge(f.path(), 1, Integer::sum);
        for (var e : seen.entrySet()) {
            if (e.getValue() > 1) return "같은 경로가 두 번 있습니다: " + e.getKey();
        }
        if (files.stream().noneMatch(f -> f.path().startsWith("src/"))) return "시작 코드(src/…)가 없습니다.";
        if (files.stream().noneMatch(f -> f.path().startsWith("test/"))) return "공개 테스트(test/…)가 없습니다.";
        if (hidden.isEmpty()) return "숨은 테스트가 없습니다.";
        if (hidden.stream().anyMatch(f -> !f.path().startsWith("test/"))) return "숨은 테스트 경로는 test/ 로 시작해야 합니다.";
        if (sol.isEmpty()) return "정답 코드(solution)가 없습니다.";
        if (sol.stream().anyMatch(f -> !f.path().startsWith("src/"))) return "정답 코드는 src/ 파일만 가능합니다.";
        return null;
    }

    boolean formatOk() {
        return formatProblem(draft) == null;
    }

    RunResult start() {
        if (start == null) start = runner.run(toMap(draft.files()));
        return start;
    }

    RunResult startHidden() {
        if (startHidden == null) startHidden = runner.run(toMap(concat(draft.files(), draft.hiddenTests())));
        return startHidden;
    }

    RunResult solved() {
        if (solved == null) {
            Map<String, String> m = toMap(draft.files());
            m.putAll(toMap(draft.solution()));
            m.putAll(toMap(draft.hiddenTests()));
            solved = runner.run(m);
        }
        return solved;
    }

    /** 컴파일 실패 등 실패 원인을 한두 줄로 */
    static String problem(RunResult r) {
        if (!r.compiled()) {
            String first = r.diagnostics().stream().filter(x -> "error".equals(x.severity())).findFirst()
                    .map(x -> x.file() + ":" + x.line() + " " + x.message()).orElse("컴파일 실패");
            return "컴파일되지 않습니다 (" + first + ")";
        }
        List<String> failed = r.tests().stream().filter(t -> !t.passed()).limit(3).map(CodeRunner.TestCase::name).toList();
        return "테스트 " + r.passed() + "개 통과, " + r.failed() + "개 실패" + (failed.isEmpty() ? "" : " " + failed);
    }

    static Map<String, String> toMap(List<DevDraft.FileDraft> files) {
        Map<String, String> m = new LinkedHashMap<>();
        for (var f : nz(files)) m.put(f.path(), f.content());
        return m;
    }

    @SafeVarargs
    private static List<DevDraft.FileDraft> concat(List<DevDraft.FileDraft>... lists) {
        List<DevDraft.FileDraft> out = new ArrayList<>();
        for (var l : lists) out.addAll(nz(l));
        return out;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
