package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 비개발 과제 검사 7개 (설계 6장) */
public final class BizCheckService {

    static final String FORMAT = "형식";
    static final String CSV = "CSV 형식";
    static final String CALC = "단답형 검산";
    static final String SOURCES = "근거 문서 2개 이상";
    static final String TRAP = "함정 근거가 자료에 있음";
    static final String LEAK = "정답 노출 없음";
    static final String SOLVE = "다른 회사 모델이 풀기";

    private static final Pattern NAME = Pattern.compile("자료/[\\p{L}\\p{N}_ ()\\-]+\\.(csv|pdf)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private BizCheckService() {}

    /** 이 Draft 하나를 위한 검사 목록. 표와 형식 검사 결과를 검사끼리 나눠 쓴다 */
    static List<GenCheckService<BizDraftDto>> forDraft(PdfService pdf, TaskGenSolverService solver, String solverModel, BizDraftDto draft) {
        return forDraft(pdf, solver, solverModel, draft, 60);
    }

    /** minutes: 제한 시간(20/40/60). 문항 개수 기준이 시간에 따라 다르다 */
    static List<GenCheckService<BizDraftDto>> forDraft(PdfService pdf, TaskGenSolverService solver, String solverModel, BizDraftDto draft, int minutes) {
        Ctx ctx = new Ctx(pdf, draft, minutes);
        return List.of(
                check(FORMAT, d -> ctx.problem == null ? ok("필수 항목과 경로가 모두 맞습니다.") : fail(ctx.problem)),
                check(CSV, d -> {
                    if (ctx.problem != null) return skipped();
                    for (var e : ctx.tables.all().entrySet()) {
                        Set<Integer> sizes = new LinkedHashSet<>();
                        e.getValue().forEach(r -> sizes.add(r.size()));
                        if (e.getValue().size() < 2) return fail(e.getKey() + " 에 데이터 행이 없습니다.");
                        if (sizes.size() > 1) return fail(e.getKey() + " 의 행마다 칸 수가 다릅니다 " + sizes);
                    }
                    return ok("CSV " + ctx.tables.all().size() + "개의 칸 수가 모두 같습니다.");
                }),
                check(CALC, d -> {
                    if (ctx.problem != null) return skipped();
                    List<String> bad = new ArrayList<>();
                    int n = 0;
                    for (var q : d.questions()) {
                        if (q.type() != BizDraftDto.Type.SHORT) continue;
                        n++;
                        try {
                            double got = CalcParserService.eval(q.calc(), ctx.tables);
                            Double want = BizTableService.num(q.accepted().get(0));
                            if (!close(got, want)) bad.add("'" + shorten(q.content()) + "' 계산 " + trim(got) + " ≠ 인정 답안 " + q.accepted().get(0));
                        } catch (IllegalArgumentException e) {
                            bad.add("'" + shorten(q.content()) + "' " + e.getMessage());
                        }
                    }
                    return bad.isEmpty() ? ok("단답형 " + n + "개 모두 계산식 결과와 인정 답안이 같습니다.") : fail(String.join(" / ", bad));
                }),
                check(SOURCES, d -> {
                    if (ctx.problem != null) return skipped();
                    List<String> bad = new ArrayList<>();
                    int i = 0;
                    for (var q : d.questions()) {
                        i++;
                        String p = sourceProblem(q, ctx);
                        if (p != null) bad.add("문항 " + i + ": " + p);
                    }
                    return bad.isEmpty() ? ok("문항 " + d.questions().size() + "개 모두 근거 문서가 2개 이상이고 경로·쪽이 있습니다.") : fail(String.join(" / ", bad));
                }),
                check(TRAP, d -> {
                    if (ctx.problem != null) return skipped();
                    String ev = squash(d.trap().evidence().strip().replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", ""));
                    if (ev.length() < 8) return fail("함정 근거 문장이 너무 짧습니다.");
                    return squash(ctx.corpus()).contains(ev) ? ok("함정 근거 문장이 자료에 그대로 있습니다.")
                            : fail("함정 근거 문장을 자료(문서·CSV)에서 찾지 못했습니다: " + shorten(d.trap().evidence()));
                }),
                check(LEAK, d -> {
                    if (ctx.problem != null) return skipped();
                    // 시험 시간 안내("60분 동안")는 정답이 아니므로 빼고 본다
                    String brief = String.join(" ", d.description()) + " " + String.join(" ", d.requirements());
                    brief = brief.replaceAll("\\d+분\\s*동안", "");
                    for (var q : d.questions()) {
                        if (q.type() != BizDraftDto.Type.SHORT) continue;
                        String a = q.accepted().get(0).strip();
                        if (Pattern.compile("(?<![\\d.])" + Pattern.quote(a) + "(?![\\d.])").matcher(brief).find()) {
                            return fail("설명·요구사항에 단답형 정답 수치 '" + a + "' 가 들어 있습니다.");
                        }
                    }
                    return ok("설명·요구사항에 단답형 정답 수치가 없습니다.");
                }),
                check(SOLVE, d -> {
                    if (ctx.problem != null) return skipped();
                    String text;
                    try {
                        text = solver.solve(solverModel, solvePrompt(d, ctx));
                    } catch (RuntimeException e) {
                        return fail("풀이 모델 호출 실패: " + e.getMessage());
                    }
                    List<String> answers = parseAnswers(text);
                    if (answers == null) return fail("풀이 모델 답변에서 answers JSON 을 찾지 못했습니다.");
                    List<String> wrong = new ArrayList<>();
                    for (int i = 0; i < d.questions().size(); i++) {
                        var q = d.questions().get(i);
                        String a = i < answers.size() ? answers.get(i) : null;
                        boolean right;
                        if (q.type() == BizDraftDto.Type.SINGLE) {
                            int want = 0;
                            for (int j = 0; j < q.choices().size(); j++) if (q.choices().get(j).correct()) want = j + 1;
                            right = a != null && a.strip().equals(String.valueOf(want));
                        } else {
                            Double got = BizTableService.num(a);
                            right = q.accepted().stream().anyMatch(x -> close(got, BizTableService.num(x)));
                        }
                        if (!right) wrong.add("Q" + (i + 1) + "(풀이 " + a + ")");
                    }
                    return wrong.isEmpty() ? ok("풀이 모델이 문항 " + d.questions().size() + "개를 모두 맞혔습니다.") : fail("풀이 모델이 틀린 문항: " + String.join(", ", wrong));
                }));
    }

    // ── 형식 규칙 ─────────────────────────────

    /** 형식에 맞으면 null. 경로 규칙은 파일 이름·경로 조작도 막는다 */
    static String formatProblem(BizDraftDto d, PdfService pdf) {
        return formatProblem(d, pdf, 60);
    }

    static String formatProblem(BizDraftDto d, PdfService pdf, int minutes) {
        if (d == null) return "과제가 비어 있습니다.";
        if (blank(d.title())) return "제목이 비어 있습니다.";
        if (isEmpty(d.description()) || d.description().stream().anyMatch(BizCheckService::blank)) return "설명(description)이 비어 있습니다.";
        if (isEmpty(d.requirements()) || d.requirements().stream().anyMatch(BizCheckService::blank)) return "요구사항(requirements)이 비어 있습니다.";
        if (d.followUps() == null || d.followUps().size() != 3 || d.followUps().stream().anyMatch(BizCheckService::blank)) return "후속 질문은 3개여야 합니다.";
        if (blank(d.checklist()) || !d.checklist().contains("필수 항목")) return "체크리스트에 '필수 항목'이 없습니다.";
        if (d.trap() == null || blank(d.trap().description()) || blank(d.trap().evidence())) return "함정 정보(trap)가 비어 있습니다.";
        if (isEmpty(d.csvFiles())) return "CSV 가 1개 이상 있어야 합니다.";
        if (d.documents() == null || d.documents().size() < 2) return "문서(PDF)가 2개 이상 있어야 합니다.";
        Set<String> paths = new LinkedHashSet<>();
        for (var f : d.csvFiles()) {
            if (f == null || f.path() == null || !NAME.matcher(f.path()).matches() || !f.path().endsWith(".csv")) return "CSV 경로 형식이 맞지 않습니다: " + (f == null ? null : f.path()) + " (자료/이름.csv)";
            if (blank(f.content())) return "CSV 내용이 비어 있습니다: " + f.path();
            if (!paths.add(f.path())) return "같은 경로가 두 번 있습니다: " + f.path();
        }
        for (var doc : d.documents()) {
            if (doc == null || doc.path() == null || !NAME.matcher(doc.path()).matches() || !doc.path().endsWith(".pdf")) return "문서 경로 형식이 맞지 않습니다: " + (doc == null ? null : doc.path()) + " (자료/이름.pdf)";
            if (!paths.add(doc.path())) return "같은 경로가 두 번 있습니다: " + doc.path();
            if (isEmpty(doc.pages()) || doc.pages().stream().anyMatch(BizCheckService::blank)) return "문서 쪽 글이 비어 있습니다: " + doc.path();
            try {
                int made = pdf.make(titleOf(doc.path()), doc.pages()).pageTexts().size();
                if (made != doc.pages().size()) return "문서 한 쪽의 글이 너무 길어 PDF 에서 쪽이 늘어납니다 (" + doc.path() + ": " + doc.pages().size() + "쪽 → " + made + "쪽). 쪽마다 900자 이내로 쓰세요.";
            } catch (IllegalArgumentException e) {
                return "문서를 PDF 로 만들 수 없습니다 (" + doc.path() + "): " + e.getMessage();
            }
        }
        if (isEmpty(d.questions())) return "문항이 없습니다.";
        int singles = 0, shorts = 0;
        for (var q : d.questions()) {
            if (q == null || q.type() == null || blank(q.content())) return "문항의 유형이나 내용이 비어 있습니다.";
            if (q.type() == BizDraftDto.Type.SINGLE) {
                singles++;
                if (q.choices() == null || q.choices().size() != 4 || q.choices().stream().anyMatch(c -> c == null || blank(c.content()))) return "객관식은 보기가 4개여야 합니다: " + shorten(q.content());
                if (q.choices().stream().filter(BizDraftDto.Choice::correct).count() != 1) return "객관식은 정답 보기가 1개여야 합니다: " + shorten(q.content());
            } else {
                shorts++;
                if (isEmpty(q.accepted()) || q.accepted().stream().anyMatch(BizCheckService::blank)) return "단답형에 인정 답안이 없습니다: " + shorten(q.content());
                if (BizTableService.num(q.accepted().get(0)) == null) return "단답형 인정 답안 첫 번째는 숫자여야 합니다: " + q.accepted().get(0);
                if (blank(q.calc())) return "단답형에 계산식(calc)이 없습니다: " + shorten(q.content());
            }
            if (q.sources() == null) return "문항에 근거 문서(sources)가 없습니다: " + shorten(q.content());
        }
        int minShorts = minutes <= 20 ? 1 : 2; // 20분 과제는 객관식 2 + 단답형 1 을 허용한다
        if (singles < 2) return "객관식이 2개 이상이어야 합니다.";
        if (shorts < minShorts) return "단답형이 " + minShorts + "개 이상이어야 합니다.";
        return null;
    }

    private static String sourceProblem(BizDraftDto.QuestionDraft q, Ctx ctx) {
        Set<String> distinct = new LinkedHashSet<>();
        for (var s : q.sources()) {
            if (s == null || s.path() == null) return "근거 문서 경로가 비어 있습니다.";
            Integer pageCount = ctx.pageCounts.get(s.path());
            boolean isCsv = ctx.tables.all().containsKey(s.path());
            if (pageCount == null && !isCsv) return "근거 문서가 자료에 없습니다: " + s.path();
            if (pageCount != null) {
                if (blank(s.pages())) return "근거 쪽 번호가 없습니다: " + s.path();
                for (String p : s.pages().split("[,\\s]+")) {
                    if (p.isEmpty()) continue;
                    int n;
                    try {
                        n = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        return "쪽 번호가 숫자가 아닙니다: " + s.path() + " " + s.pages();
                    }
                    if (n < 1 || n > pageCount) return "없는 쪽입니다: " + s.path() + " " + n + "쪽 (전체 " + pageCount + "쪽)";
                }
            }
            distinct.add(s.path());
        }
        return distinct.size() < 2 ? "근거 문서가 서로 다른 2개 이상이어야 합니다 (" + distinct.size() + "개)" : null;
    }

    // ── 풀이 ─────────────────────────────

    private static String solvePrompt(BizDraftDto d, Ctx ctx) {
        StringBuilder sb = new StringBuilder("다음 자료만 근거로 문항에 답하세요.\n");
        for (var doc : d.documents()) {
            for (int i = 0; i < doc.pages().size(); i++) sb.append("\n## ").append(doc.path()).append(" (").append(i + 1).append("쪽)\n").append(doc.pages().get(i)).append('\n');
        }
        for (var f : d.csvFiles()) sb.append("\n## ").append(f.path()).append('\n').append(f.content()).append('\n');
        sb.append("\n# 문항\n");
        int i = 1;
        for (var q : d.questions()) {
            sb.append("Q").append(i++).append(". (").append(q.type() == BizDraftDto.Type.SINGLE ? "객관식" : "단답형").append(") ").append(q.content()).append('\n');
            if (q.type() == BizDraftDto.Type.SINGLE) {
                for (int j = 0; j < q.choices().size(); j++) sb.append("  ").append(j + 1).append(") ").append(q.choices().get(j).content()).append('\n');
            }
        }
        sb.append("\n계산을 꼼꼼히 한 뒤, 마지막 줄에 JSON 하나만 출력하세요. 형식: {\"answers\": [\"2\", \"8430\", ...]} ")
                .append("(객관식은 보기 번호, 단답형은 문항이 정한 단위의 숫자만)");
        return sb.toString();
    }

    /** 답변 글 속 마지막 {"answers": [...]} 를 읽는다. 못 찾으면 null */
    static List<String> parseAnswers(String text) {
        Matcher m = Pattern.compile("\\{[^{}]*\"answers\"[^{}]*\\}").matcher(text);
        String last = null;
        while (m.find()) last = m.group();
        if (last == null) return null;
        try {
            JsonNode a = JSON.readTree(last).get("answers");
            if (a == null || !a.isArray()) return null;
            List<String> out = new ArrayList<>();
            a.forEach(x -> out.add(x.asText()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 공통 ─────────────────────────────

    /** 한 Draft 의 공통 계산: 형식 검사 결과, CSV 표, 문서 쪽 수 */
    private static final class Ctx {
        final String problem;
        final BizTableService tables;
        final Map<String, Integer> pageCounts = new LinkedHashMap<>();
        final BizDraftDto draft;

        Ctx(PdfService pdf, BizDraftDto d, int minutes) {
            this.draft = d;
            this.problem = formatProblem(d, pdf, minutes);
            this.tables = new BizTableService(d == null ? null : d.csvFiles());
            if (problem == null) d.documents().forEach(x -> pageCounts.put(x.path(), x.pages().size()));
        }

        String corpus() {
            StringBuilder sb = new StringBuilder();
            draft.documents().forEach(x -> x.pages().forEach(p -> sb.append(p).append('\n')));
            draft.csvFiles().forEach(f -> sb.append(f.content()).append('\n'));
            return sb.toString();
        }
    }

    public static String titleOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static boolean close(Double a, Double b) {
        return a != null && b != null && Math.abs(a - b) <= Math.max(0.01, Math.abs(b) * 0.005);
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static GenCheckService<BizDraftDto> check(String name, Function<BizDraftDto, GenCheckResultDto> body) {
        return new GenCheckService<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GenCheckResultDto run(BizDraftDto d) {
                GenCheckResultDto r = body.apply(d);
                return new GenCheckResultDto(name, r.ok(), r.detail());
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
        return fail("형식이 맞지 않아 확인하지 않았습니다.");
    }
}
