package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 글 검사에 쓰는 작은 도구들(핵심어 찾기, 주석·테스트 이름 뽑기). DEV·BIZ 검사가 함께 쓴다 */
final class GenTextService {

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern TEST_METHOD = Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest)\\b[\\s\\S]*?void\\s+(\\w+)\\s*\\(");
    private static final Pattern JOSA = Pattern.compile("(으로|에서|에게|까지|부터|은|는|이|가|을|를|의|에|로|과|와|도|만)$");

    private GenTextService() {}

    /** 대소문자와 모든 공백을 없앤 비교용 글 */
    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 핵심어 중 text 에 들어 있는 것(대소문자·공백 무시). 입력 순서 유지, 중복 없음 */
    static List<String> hits(List<String> keywords, String text) {
        String t = squash(text);
        List<String> out = new ArrayList<>();
        for (String k : clean(keywords)) {
            if (t.contains(squash(k)) && !out.contains(k)) out.add(k);
        }
        return out;
    }

    /** 문서 쪽 글에서 제목·표 이름처럼 보이는 짧은 줄(30자 이하, 문장 부호로 끝나지 않음) */
    static List<String> headings(List<String> pages) {
        List<String> out = new ArrayList<>();
        for (String page : pages == null ? List.<String>of() : pages) {
            for (String line : page == null ? new String[0] : page.split("\\R")) {
                String t = line.strip().replaceAll("^[#\\s*\\-]+", "");
                if (!t.isEmpty() && t.length() <= 30 && !t.matches(".*[.。?!:]$")) out.add(t);
            }
        }
        return out;
    }

    /** 핵심어 중 자료의 문서·파일 이름이나 제목(names)에 들어 있는 것을 뺀다. 설명에서 그 이름을 부른 것은 노출이 아니다 */
    static List<String> exceptNames(List<String> words, List<String> names) {
        List<String> squashed = names.stream().filter(n -> n != null).map(GenTextService::squash).toList();
        return words.stream().filter(w -> squashed.stream().noneMatch(n -> n.contains(squash(w)))).toList();
    }

    /** 비어 있지 않은 핵심어만(앞뒤 공백 제거, 중복 제거) */
    static List<String> clean(List<String> keywords) {
        Set<String> out = new LinkedHashSet<>();
        if (keywords != null) for (String k : keywords) if (k != null && !k.isBlank()) out.add(k.strip());
        return new ArrayList<>(out);
    }

    /**
     * 폼의 함정 문장에서 뽑은 핵심어. 조사를 떼고 3글자 이상만 쓰며, 주제 문장에 이미 있는 말은 뺀다
     * (주제 말은 설명에 나와도 되므로). 2글자 이하는 과탐을 막으려고 뺀다.
     */
    static List<String> formWords(String trap, String topic) {
        Set<String> out = new LinkedHashSet<>();
        if (trap == null) return List.of();
        String topicSquashed = squash(topic);
        for (String raw : trap.split("[^\\p{L}\\p{N}]+")) {
            String w = JOSA.matcher(raw).replaceFirst("");
            if (w.length() < 3) continue;
            if (!topicSquashed.isEmpty() && topicSquashed.contains(squash(w))) continue;
            out.add(w);
        }
        return new ArrayList<>(out);
    }

    /** Java 소스 속 주석(한 줄·블록)을 모두 이어 붙인 글 */
    static String comments(String source) {
        StringBuilder sb = new StringBuilder();
        for (Pattern p : List.of(LINE_COMMENT, BLOCK_COMMENT)) {
            Matcher m = p.matcher(source);
            while (m.find()) sb.append(m.group()).append('\n');
        }
        return sb.toString();
    }

    /** 테스트 파일들의 @Test 메서드 이름 */
    static List<String> testNames(List<DevDraftDto.FileDraft> tests) {
        Set<String> out = new LinkedHashSet<>();
        if (tests != null) {
            for (var f : tests) {
                if (f == null || f.content() == null) continue;
                Matcher m = TEST_METHOD.matcher(f.content());
                while (m.find()) out.add(m.group(1));
            }
        }
        return new ArrayList<>(out);
    }

    private static final int MIN_BASIS_CHARS = 8;

    /**
     * E2 판단 지점 검사(DEV·BIZ 공통). 기준 문서 M4 중 자동으로 확인할 수 있는 부분: 판단 지점이 1개 이상 있고,
     * 선택지가 2개 이상이며, 근거 문장이 응시자에게 보이는 자료(corpus)에 그대로 있다.
     * 판단 지점 필드가 아예 없으면(옛 형식, null) warn, 있는데 비었거나 근거를 못 찾으면 fail.
     * requireEvidence(DEV): 선택지마다 뒷받침하는 사실(evidence)이 보이는 자료에 실제로 있어야 한다(없으면 fail, M4)
     */
    static GenCheckResultDto judgmentPoints(List<DevDraftDto.JudgmentPoint> points, String corpus, boolean requireEvidence) {
        if (points == null) return new GenCheckResultDto("", false, "judgmentPoints 가 없는 옛 형식이라 판단 지점을 확인하지 못했어요", GenCheckResultDto.WARN);
        if (points.isEmpty()) return new GenCheckResultDto("", false, "판단 지점(judgmentPoints)이 1개 이상 있어야 합니다(사람이 정해야 하는 모호한 요구나 트레이드오프).");
        String all = squash(corpus);
        List<String> bad = new ArrayList<>();
        int i = 0;
        for (var p : points) {
            i++;
            if (p == null || p.question() == null || p.question().isBlank()) {
                bad.add("판단 지점 " + i + "에 무엇을 정해야 하는지(question)가 없습니다");
            } else if (clean(p.options()).size() < 2) {
                bad.add("판단 지점 " + i + "의 선택지(options)가 2개 이상이어야 합니다");
            } else {
                String b = squash(p.basis() == null ? "" : p.basis().strip().replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", ""));
                if (b.length() < MIN_BASIS_CHARS) bad.add("판단 지점 " + i + "의 근거 문장(basis)이 너무 짧거나 없습니다");
                else if (!all.contains(b)) bad.add("판단 지점 " + i + "의 근거 문장을 응시자 자료에서 찾지 못했습니다: " + clip(p.basis()));
                else {
                    String e = evidenceProblem(p, all, requireEvidence);
                    if (e != null) bad.add("판단 지점 " + i + e);
                }
            }
        }
        return bad.isEmpty() ? new GenCheckResultDto("", true, "판단 지점 " + points.size() + "개의 근거 문장이 응시자 자료에 있습니다.")
                : new GenCheckResultDto("", false, String.join(" / ", bad) + ". 근거 문장과 선택지별 근거 사실은 응시자에게 보이는 설명·요구사항·자료에 있는 그대로 한 줄씩 복사하세요(자료가 있다고만 쓰지 말고 값·사례를 그 자료에 실제로 넣으세요).");
    }

    /** 자료가 있다고만 가리키는 문장(사례가 있다, 정해지지 않았다, 서비스마다 다르다). 숫자·코드가 없으면 사실이 아니다 */
    private static final Pattern POINTER = Pattern.compile("(사례|기록|자료|내역)(가|는|도)?\\s*(모두\\s*)?있|정해지지\\s*않|정해져\\s*있지|다를\\s*수\\s*있|따라\\s*다르");
    private static final Pattern CONCRETE = Pattern.compile("\\d|[`\"'“”‘’]");

    /** M4: 선택지마다 근거 사실이 자료에 있는지. 문제가 없으면 null */
    private static String evidenceProblem(DevDraftDto.JudgmentPoint p, String all, boolean required) {
        List<String> ev = clean(p.evidence());
        if (ev.isEmpty()) return required ? "에 선택지별 근거 사실(evidence)이 없습니다" : null;
        // 한 문장에 선택지 둘의 근거를 함께 쓴 경우도 인정: 줄 수가 적어도 선택지마다 그 말이 근거 글에 있으면 된다(M4)
        if (ev.size() < clean(p.options()).size()) {
            String joined = squash(String.join(" ", ev));
            for (String o : clean(p.options())) {
                if (Arrays.stream(o.split("[^\\p{L}\\p{N}]+")).map(w -> JOSA.matcher(w).replaceFirst("")).filter(w -> w.length() >= 2).noneMatch(w -> joined.contains(squash(w))))
                    return "의 근거 사실(evidence)에 선택지 '" + clip(o) + "' 의 근거가 없습니다";
            }
        }
        for (String e : ev) {
            String t = e.strip().replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "");
            if (!all.contains(squash(t))) return "의 근거 사실을 응시자 자료에서 찾지 못했습니다: " + clip(e);
            if (POINTER.matcher(t).find() && !CONCRETE.matcher(t).find()) return "의 근거 사실이 자료가 있다고만 가리킵니다(실제 사실·값이 없음): " + clip(e);
        }
        return null;
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
