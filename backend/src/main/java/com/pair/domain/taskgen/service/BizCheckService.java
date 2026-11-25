package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
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

/** 비개발 과제 검사 12개 (설계 6장 + 검수 결과로 더한 3개) */
public final class BizCheckService {

    // 기준 문서(pAIr-docs 설계/좋은_과제_기준.md) 번호 대응. M5 는 BizSoloSolveService 가 맡는다(afterChecks).
    static final String FORMAT = "형식"; // M1 전제(5장 형식, 문항 구성)
    static final String CSV = "CSV 형식"; // M1 전제
    static final String CALC = "단답형 검산"; // M1, 4장 BIZ(모든 계산을 자료의 숫자로 다시 할 수 있다)
    static final String SOURCES = "근거 문서 2개 이상"; // M1, Q4, 4장 BIZ
    static final String TRAP = "함정 근거가 자료에 있음"; // Q3(함정의 근거가 자료에 사실로 있다)
    static final String LEAK = "정답 노출 없음"; // M4(2장 경계: 설명·요구사항에 정답 수치)
    static final String SOLVE = "다른 회사 모델이 풀기"; // M1(풀 수 있는지). M5 가 아니다
    static final String TRAP_WORDS = "함정 핵심어 노출 없음"; // M4
    static final String WRONG_ANSWERS = "단답 오답이 인정 답안과 다름"; // M2(인정 답안), M3(오답이 함정 값)
    static final String FOLLOW_UP = "후속 질문이 과제에 맞음"; // M4
    static final String EVIDENCE_STYLE = "함정 근거가 사실 진술임"; // M4, 4장 BIZ(사실 진술, 해석 지시 금지)
    static final String CONCLUSION = "함정이 결론을 바꿈"; // M3

    /** 근거 문장이 응시자에게 해석을 지시하는 말투 */
    private static final Pattern DIRECTIVE = Pattern.compile("판단하지\\s*않|해서는\\s*안|하면\\s*안\\s*된|혼동하지|만으로.*하지\\s*않");

    private static final Pattern NAME = Pattern.compile("자료/[\\p{L}\\p{N}_ ()\\-]+\\.(csv|pdf)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private BizCheckService() {}

    /** 이 Draft 하나를 위한 검사 목록. 표와 형식 검사 결과를 검사끼리 나눠 쓴다 */
    static List<GenCheckService<BizDraftDto>> forDraft(PdfService pdf, TaskGenSolverService solver, String solverModel, BizDraftDto draft) {
        return forDraft(pdf, solver, solverModel, draft, 60);
    }

    /** minutes: 제한 시간(20/40/60). 문항 개수 기준이 시간에 따라 다르다 */
    static List<GenCheckService<BizDraftDto>> forDraft(PdfService pdf, TaskGenSolverService solver, String solverModel, BizDraftDto draft, int minutes) {
        return forDraft(pdf, solver, solverModel, draft, minutes, null);
    }

    /** form: 폼의 함정 문장에서 핵심어를 더 뽑는 데 쓴다. null 이면 모델이 정한 핵심어만 본다 */
    static List<GenCheckService<BizDraftDto>> forDraft(PdfService pdf, TaskGenSolverService solver, String solverModel, BizDraftDto draft, int minutes, TaskGenRequest form) {
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
                }),
                check(TRAP_WORDS, d -> {
                    if (ctx.problem != null) return skipped();
                    return trapWords(d, form);
                }),
                check(WRONG_ANSWERS, d -> {
                    if (ctx.problem != null) return skipped();
                    return wrongAnswers(d);
                }),
                check(FOLLOW_UP, d -> {
                    if (ctx.problem != null) return skipped();
                    return followUp(d);
                }),
                check(EVIDENCE_STYLE, d -> {
                    if (ctx.problem != null) return skipped();
                    return evidenceStyle(d);
                }),
                check(CONCLUSION, d -> {
                    if (ctx.problem != null) return skipped();
                    return conclusion(d);
                }));
    }

    // ── 새 검사들 ─────────────────────────────

    /** 함정 핵심어가 설명, 요구사항, 자료 파일 이름에 있으면 실패 (자료 본문에는 함정 근거가 있어야 하므로 보지 않는다) */
    private static GenCheckResultDto trapWords(BizDraftDto d, TaskGenRequest form) {
        List<String> keywords = GenTextService.clean(d.trapKeywords());
        if (keywords.size() < 2) return fail("함정 핵심어(trapKeywords)가 2~5개 있어야 합니다 (지금 " + keywords.size() + "개).");
        List<String> words = new ArrayList<>(keywords);
        if (form != null) {
            for (String w : GenTextService.formWords(form.trap(), form.topic())) if (!words.contains(w)) words.add(w);
        }
        StringBuilder paths = new StringBuilder();
        d.csvFiles().forEach(f -> paths.append(f.path()).append(' '));
        d.documents().forEach(f -> paths.append(f.path()).append(' '));
        Map<String, List<String>> found = new LinkedHashMap<>();
        found.put("설명", GenTextService.hits(words, String.join(" ", d.description())));
        found.put("요구사항", GenTextService.hits(words, String.join(" ", d.requirements())));
        found.put("자료 파일 이름", GenTextService.hits(words, paths.toString()));
        List<String> parts = new ArrayList<>();
        found.forEach((where, hit) -> {
            if (!hit.isEmpty()) parts.add(where + "에 " + hit.stream().map(h -> "'" + h + "'").toList());
        });
        return parts.isEmpty() ? ok("함정 핵심어 " + keywords.size() + "개가 설명·요구사항·자료 이름에 없습니다.")
                : fail("응시자에게 보이는 곳에 함정 핵심어가 드러납니다: " + String.join(" / ", parts) + ". 이 말을 쓰지 말고 현상만 설명하세요.");
    }

    /** 단답형마다 흔한 오답이 있어야 하고, 오답이 인정 답안과 같거나 0.5% 안이면 실패 */
    private static GenCheckResultDto wrongAnswers(BizDraftDto d) {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (var q : d.questions()) {
            if (q.type() != BizDraftDto.Type.SHORT) continue;
            n++;
            List<BizDraftDto.WrongAnswer> wrong = q.commonWrongAnswers() == null ? List.of() : q.commonWrongAnswers();
            if (wrong.isEmpty()) {
                bad.add("'" + shorten(q.content()) + "' 에 흔한 오답(commonWrongAnswers)이 없습니다");
                continue;
            }
            for (var w : wrong) {
                Double v = w == null ? null : BizTableService.num(w.value());
                if (v == null) {
                    bad.add("'" + shorten(q.content()) + "' 의 흔한 오답이 숫자가 아닙니다: " + (w == null ? null : w.value()));
                } else if (q.accepted().stream().anyMatch(a -> close(v, BizTableService.num(a)))) {
                    bad.add("'" + shorten(q.content()) + "' 의 흔한 오답 " + w.value() + " 가 인정 답안과 같아 정답으로 처리됩니다");
                }
            }
        }
        return bad.isEmpty() ? ok("단답형 " + n + "개의 흔한 오답이 모두 인정 답안과 다릅니다.") : fail(String.join(" / ", bad));
    }

    /** 함정 근거 문장에 "~만으로 판단하지 않는다" 같은 해석 지시가 있으면 경고(warn) */
    static GenCheckResultDto evidenceStyle(BizDraftDto d) {
        Matcher m = DIRECTIVE.matcher(d.trap().evidence());
        return m.find() ? warnResult("함정 근거 문장이 사실 진술이 아니라 해석 지시처럼 쓰였어요: '" + m.group() + "'. 측정 범위나 집계 정의 같은 사실로 쓰고 다른 내용 속에 섞으세요")
                : ok("함정 근거 문장에 해석 지시 말투가 없습니다.");
    }

    /** 함정에 빠진 결론이 정답 결론과 같으면 fail(다시 생성), 결론 필드가 없으면 warn */
    static GenCheckResultDto conclusion(BizDraftDto d) {
        String right = d.trap().correctConclusion(), wrong = d.trap().wrongConclusion();
        if (blank(right) || blank(wrong)) return warnResult("trap.correctConclusion·wrongConclusion 이 없어 함정이 결론을 바꾸는지 확인하지 못했어요");
        if (normalize(right).equals(normalize(wrong))) {
            return fail("함정에 빠진 계산의 결론과 정답 결론이 같습니다(" + shorten(right) + "). 함정에 빠지면 추천안·통과 여부가 달라지도록 수치를 다시 정하세요.");
        }
        return ok("함정에 빠지면 결론이 달라집니다.");
    }

    private static String normalize(String s) {
        return s.replaceAll("[\\s\\p{Punct}。、·…]+", "").toLowerCase();
    }

    /** 3번째 후속 질문이 과제 맞춤이어야 하고 함정 핵심어가 들어 있으면 안 된다 */
    private static GenCheckResultDto followUp(BizDraftDto d) {
        String q = d.followUps().get(2); // 형식 검사가 3개임을 확인했다
        List<String> hits = GenTextService.hits(d.trapKeywords(), q);
        if (!hits.isEmpty()) {
            return fail("3번째 후속 질문에 정답을 드러내는 말이 있습니다: " + hits.stream().map(h -> "'" + h + "'").toList()
                    + ". 핵심어 없이 검증 방법만 묻도록 고치세요.");
        }
        if (d.followUps().get(0).strip().equals(q.strip()) || d.followUps().get(1).strip().equals(q.strip())) {
            return fail("3번째 후속 질문이 앞의 공용 질문과 같습니다. 이 과제의 함정에 맞춘 질문 1개를 쓰세요.");
        }
        return ok("3번째 후속 질문이 따로 있고 핵심어가 없습니다.");
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

    static boolean close(Double a, Double b) {
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
                return new GenCheckResultDto(name, r.ok(), r.detail(), r.level());
            }
        };
    }

    private static GenCheckResultDto ok(String detail) {
        return new GenCheckResultDto("", true, detail);
    }

    private static GenCheckResultDto fail(String detail) {
        return new GenCheckResultDto("", false, detail);
    }

    /** 통과하지 못했지만 다시 생성하지 않고 알리기만 하는 결과 */
    private static GenCheckResultDto warnResult(String detail) {
        return new GenCheckResultDto("", false, detail, GenCheckResultDto.WARN);
    }

    private static GenCheckResultDto skipped() {
        return fail("형식이 맞지 않아 확인하지 않았습니다.");
    }
}
