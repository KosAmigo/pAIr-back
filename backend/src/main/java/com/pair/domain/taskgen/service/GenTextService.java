package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import java.util.ArrayList;
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
}
