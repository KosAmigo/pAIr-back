package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
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

/** 비개발 과제 검사 14개 (설계 6장 + 검수 결과로 더한 3개 + E2·E3 검사 2개) */
public final class BizCheckService {

    // 기준 문서(pAIr-docs 설계/좋은_과제_기준.md, 2026-11-24 개정) 번호 대응.
    // 참고 지표(AI 단독 풀이)는 합격·불합격이 아니라 BizSoloSolveService 가 afterChecks 로 기록만 한다.
    static final String FORMAT = "형식"; // M1 전제(5장 형식, 문항 구성)
    static final String CSV = "CSV 형식"; // M1 전제
    static final String CALC = "단답형 검산"; // M1, E4(모든 계산을 자료의 숫자로 다시 할 수 있다)
    static final String SOURCES = "근거 문서 2개 이상"; // M1, E4, Q4
    static final String TRAP = "함정 근거가 자료에 있음"; // E3, Q3(함정의 근거가 자료에 사실로 있다)
    static final String LEAK = "정답 노출 없음"; // M5(3장 경계: 설명·요구사항에 정답 수치)
    static final String SOLVE = "다른 회사 모델이 풀기"; // M1(풀 수 있는지)
    static final String TRAP_WORDS = "함정 핵심어 노출 없음"; // M5
    static final String WRONG_ANSWERS = "단답 오답이 인정 답안과 다름"; // M2(인정 답안), M3(오답이 함정 값)
    static final String FOLLOW_UP = "후속 질문이 과제에 맞음"; // E5, M5
    static final String EVIDENCE_STYLE = "함정 근거가 사실 진술임"; // M5, 5장 BIZ(사실 진술, 해석 지시 금지)
    static final String CONCLUSION = "함정이 결론을 바꿈"; // M3
    static final String JUDGMENT = "판단 지점과 근거가 자료에 있음"; // E2, M4(존재·근거 위치만 자동 확인, 적절성은 검수)
    static final String HIDDEN_FLAW = "숨은 결함이 답·결론을 가름"; // E3, M3
    static final String NO_PROCEDURE = "정의·문항에 계산 절차 없음"; // M5 (지표 정의는 무엇을 세는지까지만)
    static final String TRAP_QUESTION = "함정 문항이 자료 대조를 요구함"; // E3, M3 (단순 조회·같은 계산 반복 금지)

    /** 근거 문장이 응시자에게 해석을 지시하는 말투 */
    private static final Pattern DIRECTIVE = Pattern.compile("판단하지\\s*않|해서는\\s*안|하면\\s*안\\s*된|혼동하지|만으로.*하지\\s*않"
            + "|(잡히|합산되|포함되|반영되|인정되)지\\s*않(는다|습니다|음)" // 규칙 문장("실적으로 잡히지 않는다")
            + "|원인(은|이)|때문에[^.\\n]{0,30}(과대|과소|부풀|왜곡)|서로\\s*다른\\s*관측값"); // 원인 설명

    /** 지표·용어 정의나 문항이 값을 합치는 방법(차이, 곱, 가중, 비율의 금액)을 알려 주는 말 */
    private static final Pattern PROCEDURE = Pattern.compile("(차이|곱|합산|가중\\s*(평균|합))(을|를|이|가)?\\s*(뜻|의미|말합|가리킵|해당)"
            + "|비율에\\s*해당하는\\s*(금액|값|수)|각각\\s*곱해|(를|을)\\s*(빼서|빼고|나누어|나눠|곱해|곱하여)\\s*(구|계산)"
            + "|(뺀|나눈|곱한|더한)\\s*(값|것)(을|이|으로)?\\s*(뜻|의미|말합|합니다)");

    // M5: 정의에 계산 방법("A 대비 B의 상대적 증가 정도를 뜻한다", "A를 B로 나눈 값")
    private static final Pattern DEFINITION_CALC = Pattern.compile("[은는][^.\\n]{0,80}(기준으로\\s*(본|하여|해)|대비)[^.\\n]{0,60}(뜻|의미|말합|가리킵)"
            + "|(을|를)[^.\\n]{0,30}(으로|로)\\s*나눈\\s*(값|비율|몫)");
    // M5: 두 대상의 차이를 직접 말하는 문장("A와 B는 (서로) 다르다")
    private static final Pattern DIFFERENCE = Pattern.compile("(과|와)[^.\\n]{1,40}(은|는|이|가)\\s*(서로\\s*)?(다릅니다|다르다|다르며|다르지만)");
    // M5: 원인 표지, 지시 말투. 결과물 쓰기 안내("작성해야 한다")는 제외
    private static final Pattern CAUSE_LABEL = Pattern.compile("원인\\s*[:：]");
    private static final Pattern MUST = Pattern.compile("해야\\s*(한다|합니다)");
    private static final Pattern MUST_OK = Pattern.compile("(제출|작성|포함|기재|표시|명시|선택|설명|구분|담|답|입력|기록|정리)(해야|하여야)");

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
                }),
                // 결과마다 등급이 다르다(옛 형식 필드 없음 = warn, 있는데 문제 = fail)
                ownLevel(JUDGMENT, d -> {
                    if (ctx.problem != null) return skipped();
                    return GenTextService.judgmentPoints(d.judgmentPoints(), ctx.visibleCorpus(), false);
                }),
                ownLevel(HIDDEN_FLAW, d -> {
                    if (ctx.problem != null) return skipped();
                    return hiddenFlaws(d);
                }),
                check(NO_PROCEDURE, d -> {
                    if (ctx.problem != null) return skipped();
                    return noProcedure(d);
                }),
                check(TRAP_QUESTION, d -> {
                    if (ctx.problem != null) return skipped();
                    return trapQuestions(d, ctx.corpus());
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
        // 자료의 문서·파일 이름과 문서 제목(예: 비교표)을 부른 것은 노출이 아니다. 자료 파일 이름 검사에는 그대로 쓴다
        List<String> names = new ArrayList<>(d.csvFiles().stream().map(DevDraftDto.FileDraft::path).toList());
        d.documents().forEach(f -> { names.add(f.path()); names.addAll(GenTextService.headings(f.pages())); });
        List<String> textWords = GenTextService.exceptNames(words, names);
        found.put("설명", GenTextService.hits(textWords, String.join(" ", d.description())));
        found.put("요구사항", GenTextService.hits(textWords, String.join(" ", d.requirements())));
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

    /** 함정 근거 문장과 자료 글(문서 쪽 글, 설명, 요구사항)에 해석 지시·규칙 문장·원인 설명이 있으면 경고(warn) */
    static GenCheckResultDto evidenceStyle(BizDraftDto d) {
        Matcher m = DIRECTIVE.matcher(d.trap().evidence());
        if (m.find()) return warnResult("함정 근거 문장이 사실 진술이 아니라 해석 지시처럼 쓰였어요: '" + m.group() + "'. 측정 범위나 집계 정의 같은 사실로 쓰고 다른 내용 속에 섞으세요");
        for (String t : texts(d)) {
            for (String sentence : DevCheckService.sentences(t)) {
                if (DIRECTIVE.matcher(sentence).find()) {
                    return warnResult("자료 글에 규칙 문장이나 원인 설명이 있어요: '" + shorten(sentence) + "'. 측정 범위나 집계 정의 같은 사실만 쓰세요");
                }
            }
        }
        return ok("함정 근거 문장과 자료 글에 해석 지시·규칙·원인 문장이 없습니다.");
    }

    /** 설명, 요구사항, 문서 쪽 글, 문항 문구 */
    private static List<String> texts(BizDraftDto d) {
        List<String> out = new ArrayList<>(d.description());
        out.addAll(d.requirements());
        d.documents().forEach(x -> out.addAll(x.pages()));
        d.questions().forEach(q -> out.add(q.content()));
        return out;
    }

    /** M5: 정의·문항이 계산법을 말하거나, 두 대상의 차이를 직접 말하거나, "원인:"·"~해야 한다" 말투면 문장 이유를 돌려주고 아니면 null */
    private static String m5Reason(String sentence, boolean candidateText) {
        if (PROCEDURE.matcher(sentence).find() || DEFINITION_CALC.matcher(sentence).find()) return "계산 방법";
        if (DIFFERENCE.matcher(sentence).find()) return "차이를 직접 말함";
        if (CAUSE_LABEL.matcher(sentence).find()) return "원인 표지";
        // 자료(문서) 속 "~해야 한다"는 판단 지점 근거 같은 사실 서술이 많아 보지 않는다(AI 판정이 본다). 설명·요구사항·문항만 본다
        if (candidateText && MUST.matcher(sentence).find() && !MUST_OK.matcher(sentence).find()) return "지시 말투";
        return null;
    }

    /** M5: 지표·용어 정의와 문항 문구가 값을 합치는 방법(차이, 곱, 가중, 비율의 금액)을 말하면 fail */
    static GenCheckResultDto noProcedure(BizDraftDto d) {
        List<String> found = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        d.documents().forEach(x -> docs.addAll(x.pages()));
        for (String t : texts(d)) {
            boolean candidateText = !docs.contains(t);
            for (String sentence : DevCheckService.sentences(t)) {
                String why = m5Reason(sentence, candidateText); // M5
                if (why != null) found.add("'" + shorten(sentence) + "'(" + why + ")");
            }
        }
        return found.isEmpty() ? ok("정의·문항에 값을 합치는 방법이 없습니다.")
                : fail("정의나 문항이 계산 절차·차이·원인·지시를 알려 줍니다: " + String.join(", ", found.stream().limit(3).toList())
                        + ". 정의는 무엇을 세는지(대상과 범위)까지만 쓰고, 합치는 방법과 두 대상이 다르다는 말은 빼서 자료를 대조해야 드러나게 하세요.");
    }

    /**
     * E3, M3: 함정 문항(숨은 결함이 가리키는 문항, 흔한 오답이 있는 단답형)은 자료 하나를 읽어서는 안 풀려야 한다.
     * 단답형은 계산식이 칸 하나를 그대로 읽는 것이면 fail, 객관식은 정답 보기가 자료 문장 그대로면 fail.
     * 단답형끼리 계산식이나 답이 같으면(같은 계산 반복) fail
     */
    static GenCheckResultDto trapQuestions(BizDraftDto d, String corpus) {
        Set<Integer> trap = new LinkedHashSet<>();
        if (d.hiddenFlaws() != null) {
            for (var f : d.hiddenFlaws()) {
                if (f == null || f.caughtBy() == null) continue;
                Matcher m = Pattern.compile("(?:Q|문항\\s*)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(f.caughtBy());
                while (m.find()) trap.add(Integer.parseInt(m.group(1)));
            }
        }
        String all = squash(corpus);
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < d.questions().size(); i++) {
            var q = d.questions().get(i);
            boolean isTrap = trap.contains(i + 1) || (q.type() == BizDraftDto.Type.SHORT && q.commonWrongAnswers() != null && !q.commonWrongAnswers().isEmpty());
            if (!isTrap) continue;
            if (q.type() == BizDraftDto.Type.SHORT) {
                String expr = q.calc().replaceAll("v\\(\"[^\"]*\"\\s*,\\s*\"[^\"]*\"\\s*,\\s*\"[^\"]*\"\\s*\\)", "V")
                        .replaceAll("round\\(|,\\s*\\d+\\)|[()\\s]", "");
                if (expr.equals("V")) bad.add("Q" + (i + 1) + " 는 자료 칸 하나를 그대로 읽는 문항입니다");
            } else {
                for (var c : q.choices()) {
                    String t = squash(c.content());
                    if (c.correct() && t.length() >= 10 && all.contains(t)) bad.add("Q" + (i + 1) + " 의 정답 보기가 자료 문장 그대로입니다");
                }
            }
        }
        for (int i = 0; i < d.questions().size(); i++) {
            for (int j = i + 1; j < d.questions().size(); j++) {
                var a = d.questions().get(i);
                var b = d.questions().get(j);
                if (a.type() != BizDraftDto.Type.SHORT || b.type() != BizDraftDto.Type.SHORT) continue;
                if (squash(a.calc()).equals(squash(b.calc())) || close(BizTableService.num(a.accepted().get(0)), BizTableService.num(b.accepted().get(0)))) {
                    bad.add("Q" + (i + 1) + " 와 Q" + (j + 1) + " 가 같은 계산입니다");
                }
            }
        }
        return bad.isEmpty() ? ok("함정 문항이 자료 대조를 요구하고 같은 계산이 반복되지 않습니다.")
                : fail(String.join(" / ", bad) + ". 함정 문항은 서로 다른 자료 둘 이상을 대조해야 답이 나오게, 문항마다 다른 계산을 묻게 고치세요.");
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

    /** E5: 3번째 후속 질문이 과제 맞춤이어야 하고 함정 핵심어가 들어 있으면 안 된다 */
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

    /**
     * E3: 숨은 결함이 1개 이상 선언돼 있고, 결함마다 설명·대조 방법이 있으며, 결함을 놓치면 답이나 결론이 달라진다.
     * caughtBy 는 "Q2" 처럼 문항 번호나 "결론". 단답형은 흔한 오답(함정에 빠진 값)이 있어야 하고(값이 정답과 다른지는 WRONG_ANSWERS 가 본다),
     * 객관식·결론은 함정 결론이 정답 결론과 달라야 한다(CONCLUSION 과 같은 기준). 필드가 없으면(옛 형식, null) warn
     */
    static GenCheckResultDto hiddenFlaws(BizDraftDto d) {
        if (d.hiddenFlaws() == null) return warnResult("hiddenFlaws 가 없는 옛 형식이라 숨은 결함이 답·결론을 가르는지 확인하지 못했어요");
        if (d.hiddenFlaws().isEmpty()) return fail("숨은 결함(hiddenFlaws)이 1개 이상 있어야 합니다(AI 기본 답이 놓치기 쉬운 곳).");
        boolean conclusionDiffers = conclusion(d).ok();
        List<String> bad = new ArrayList<>();
        int i = 0;
        for (var f : d.hiddenFlaws()) {
            i++;
            if (f == null || blank(f.description())) { bad.add("숨은 결함 " + i + "에 설명(description)이 없습니다"); continue; }
            if (blank(f.verifyWith())) { bad.add("숨은 결함 " + i + "에 대조 방법(verifyWith)이 없습니다"); continue; }
            String by = f.caughtBy() == null ? "" : f.caughtBy();
            boolean effect = false, anyRef = false;
            Matcher m = Pattern.compile("(?:Q|문항\\s*)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(by);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n < 1 || n > d.questions().size()) continue;
                anyRef = true;
                var q = d.questions().get(n - 1);
                if (q.type() == BizDraftDto.Type.SHORT ? q.commonWrongAnswers() != null && !q.commonWrongAnswers().isEmpty() : conclusionDiffers) effect = true;
            }
            if (by.contains("결론")) {
                anyRef = true;
                if (conclusionDiffers) effect = true;
            }
            if (!anyRef) bad.add("숨은 결함 " + i + "이 어느 문항(Q번호)이나 결론에서 드러나는지(caughtBy) 없습니다");
            else if (!effect) bad.add("숨은 결함 " + i + "을 놓쳐도 답·결론이 달라지지 않습니다(단답형은 흔한 오답, 객관식·결론은 함정 결론이 정답 결론과 달라야 함)");
        }
        return bad.isEmpty() ? ok("숨은 결함 " + d.hiddenFlaws().size() + "개를 놓치면 답이나 결론이 달라집니다.") : fail(String.join(" / ", bad));
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

        /** 응시자에게 보이는 글: 설명, 요구사항, 문서, CSV */
        String visibleCorpus() {
            return String.join("\n", draft.description()) + "\n" + String.join("\n", draft.requirements()) + "\n" + corpus();
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

    /** 결과마다 등급이 다른 검사(body 가 돌려준 결과의 level 을 그대로 쓴다) */
    private static GenCheckService<BizDraftDto> ownLevel(String name, Function<BizDraftDto, GenCheckResultDto> body) {
        return check(name, body);
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
