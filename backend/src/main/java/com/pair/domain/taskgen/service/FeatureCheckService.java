package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEV: 설명·요구사항이 "추가·구현하라"고 한 메서드가 시작 코드에 이미 구현돼 있는지(코드 검사). 이미 있는 기능을 만들라고 하면 응시자가 헷갈린다(C1).
 * 구현돼 있다는 것은 틀(빈 본문, UnsupportedOperationException 등을 던지기만 함)이 아니라는 뜻이다. 모델을 부르지 않는다.
 * 설계도 api 에서 "추가:" 로 적은 메서드가 구현돼 있으면 시작 코드 단계의 잘못이고, 그 밖이면 작성 단계의 잘못이다.
 */
final class FeatureCheckService {

    static final String NAME = "추가하라는 기능이 시작 코드에 이미 구현돼 있지 않음";
    static final String CODE_STAGE = "[시작 코드 단계]";
    static final String WRITING_STAGE = "[작성 단계]";

    /** 메서드 이름 뒤 괄호 */
    private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\s*\\([^()]*\\)");
    /** 메서드 호출 바로 뒤에 "을/를 추가·구현·작성·만들" 이 붙을 때만(뒤 문장의 다른 동사에 끌리지 않게) */
    private static final Pattern ASK = Pattern.compile("^\\s*(?:을|를|은|는)?\\s*(?:메서드(?:를|을)?\\s*)?(?:새로\\s*)?(?:추가|구현|작성|만들)");
    private static final Pattern STUB = Pattern.compile("^(throw\\s+new\\s+\\w+\\([^)]*\\)\\s*;)?$");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");

    private FeatureCheckService() {}

    static GenCheckResultDto check(BlueprintDto bp, String instructionText, List<DevDraftDto.FileDraft> code) {
        Set<String> asked = asked(instructionText);
        List<String> bad = new ArrayList<>();
        boolean codeFault = false;
        for (String name : asked) {
            String where = implementedIn(name, code);
            if (where == null) continue;
            boolean planned = bp != null && bp.api().stream().anyMatch(a -> a.strip().startsWith("추가") && a.contains(name + "("));
            codeFault |= planned;
            bad.add(name + "() 을(를) 추가·구현하라고 쓰여 있는데 시작 코드(" + where + ")에 이미 구현돼 있습니다");
        }
        if (bad.isEmpty()) return new GenCheckResultDto(NAME, true, "추가·구현하라는 메서드가 시작 코드에 구현돼 있지 않습니다.");
        return new GenCheckResultDto(NAME, false, (codeFault ? CODE_STAGE : WRITING_STAGE) + " " + String.join(" / ", bad)
                + (codeFault ? ". 설계도에서 '추가:' 로 적은 메서드는 시작 코드에 시그니처 그대로 두고 본문은 throw new UnsupportedOperationException(\"TODO\"); 한 줄 틀로만 둔다." : ". 이미 있는 메서드는 추가하라고 쓰지 않는다.")).withStages(codeFault ? "CODE" : "WRITING");
    }

    /** 문장마다 "name(...) … 추가/구현" 꼴인 메서드 이름들. "기존 name" 은 이미 있다는 말이라 뺀다 */
    static Set<String> asked(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String sentence : text.split("(?<=[.。!?])\\s+|\\R")) {
            Matcher m = CALL.matcher(sentence);
            while (m.find()) {
                String name = m.group(1);
                String before = sentence.substring(Math.max(0, m.start() - 6), m.start());
                if (before.contains("기존")) continue;
                if (presentedAsExisting(sentence, m.start(), m.end())) continue;
                if (ASK.matcher(sentence.substring(m.end())).find()) out.add(name);
            }
        }
        return out;
    }

    private static final Pattern EXISTING = Pattern.compile("기존|이미 있|이미 구현|공개 API\\s*인");
    private static final Pattern ALONGSIDE = Pattern.compile("^\\s*(?:을|를|은|는)?\\s*(?:에\\s*더해|에\\s*이어|외에|말고|와\\s*별도로)");

    /** 문장이 이 메서드를 이미 있는 것으로 소개하는가: 앞(바로 앞 호출 뒤부터)에 "기존·이미 있는·공개 API인", 뒤에 "에 더해·외에" */
    private static boolean presentedAsExisting(String sentence, int start, int end) {
        String before = sentence.substring(0, start);
        int p = before.lastIndexOf(')');
        if (p >= 0) before = before.substring(p + 1);
        return EXISTING.matcher(before).find() || ALONGSIDE.matcher(sentence.substring(end)).find();
    }

    /** name 메서드가 틀이 아닌 본문으로 구현된 파일 경로(없으면 null) */
    static String implementedIn(String name, List<DevDraftDto.FileDraft> code) {
        Pattern decl = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|abstract|default)\\s+)*[\\w<>\\[\\],.? ]+\\s+" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws[^{;]*)?\\{");
        for (var f : code == null ? List.<DevDraftDto.FileDraft>of() : code) {
            if (f == null || f.path() == null || f.content() == null || !f.path().startsWith("src/")) continue;
            Matcher m = decl.matcher(f.content());
            while (m.find()) {
                String body = body(f.content(), m.end());
                if (body != null && !STUB.matcher(COMMENTS.matcher(body).replaceAll("").strip()).matches()) return f.path();
            }
        }
        return null;
    }

    private static final Pattern ANY_DECL = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|abstract|default)\\s+)*[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{;]*)?\\{");

    /** 설계도 api 의 "추가: 클래스.이름(타입 이름, ...): 반환" 에서 이름과 매개변수 타입 목록. 추가 항목이 아니면 null */
    static Object[] addedSignature(String api) {
        if (api == null || !api.strip().startsWith("추가")) return null;
        String sig = stripBody(api.substring(api.indexOf(':') + 1).strip());
        int open = sig.indexOf('(');
        if (open < 0) return null;
        String head = sig.substring(0, open).strip();
        String name = head.substring(Math.max(head.lastIndexOf('.'), head.lastIndexOf(' ')) + 1).trim(); // "클래스.이름" 이 정석, "pkg.Class.public static long 이름" 처럼 수정자·반환형이 붙어도 이름만
        int depth = 0, close = -1;
        for (int i = open; i < sig.length() && close < 0; i++) {
            if (sig.charAt(i) == '(') depth++;
            else if (sig.charAt(i) == ')' && --depth == 0) close = i;
        }
        return new Object[] {name, paramTypes(sig.substring(open + 1, close < 0 ? sig.length() : close))};
    }

    /** 설계도 시그니처 뒤에 본문 "{ ... }" 이나 ";" 가 붙어 있으면 뗀다(시그니처만 남긴다) */
    static String stripBody(String sig) {
        if (sig == null) return "";
        int brace = sig.indexOf('{');
        String s = brace >= 0 ? sig.substring(0, brace) : sig;
        return s.strip().replaceAll(";+$", "").strip();
    }

    /** "String a, Map<String, Integer> b" → [String, Map<String,Integer>] (이름 뺀 타입, 공백 없음) */
    static List<String> paramTypes(String params) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : (params == null ? "" : params).toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(typeOf(cur.toString()));
                cur.setLength(0);
            } else cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(typeOf(cur.toString()));
        return out;
    }

    private static String typeOf(String param) {
        String p = param.replaceAll("\\bfinal\\b", "").strip();
        int sp = p.lastIndexOf(' ');
        // 마지막 낱말이 매개변수 이름이면 뗀다(타입만 적은 경우는 그대로). 제네릭 안의 공백은 건드리지 않는다
        String type = sp > 0 && p.lastIndexOf('>') < sp ? p.substring(0, sp) : p;
        return type.replaceAll("\\s+", "");
    }

    /**
     * 설계도 "추가:" 메서드가 시작 코드에 틀로 있는지. 결과: null 이면 문제 없음, 아니면 문제 설명.
     * 없음, 시그니처(매개변수 타입)가 다름, 틀이 아닌 실제 구현은 모두 문제다
     */
    static String stubProblem(String api, List<DevDraftDto.FileDraft> code) {
        Object[] sig = addedSignature(api);
        if (sig == null) return null;
        String name = (String) sig[0];
        @SuppressWarnings("unchecked")
        List<String> want = (List<String>) sig[1];
        boolean named = false, matched = false;
        List<String> declared = new ArrayList<>();
        for (var f : code == null ? List.<DevDraftDto.FileDraft>of() : code) {
            if (f == null || f.path() == null || f.content() == null || !f.path().startsWith("src/")) continue;
            Matcher m = ANY_DECL.matcher(f.content());
            while (m.find()) {
                if (!m.group(1).equals(name)) continue;
                named = true;
                declared.add(f.path() + " " + name + "(" + String.join(", ", paramTypes(m.group(2))) + ")");
                if (!paramTypes(m.group(2)).equals(want)) continue;
                matched = true;
                String body = body(f.content(), m.end());
                if (body != null && !STUB.matcher(COMMENTS.matcher(body).replaceAll("").strip()).matches()) {
                    return name + "(" + String.join(", ", want) + ") 가 틀이 아니라 구현돼 있습니다(" + f.path() + ")";
                }
            }
        }
        if (matched) return null;
        String wanted = "설계도 시그니처 [" + api + "] → 비교 값 " + name + "(" + String.join(", ", want) + ")";
        return named ? wanted + " 인데 시작 코드에는 이름만 같고 매개변수 타입이 다릅니다: " + String.join(" | ", declared)
                : wanted + " 가 시작 코드에 없습니다";
    }

    /** open 은 '{' 바로 뒤. 짝이 맞는 '}' 까지의 본문 */
    static String bodyOf(String s, int open) {
        return body(s, open);
    }

    /** 본문이 틀(비었거나 예외만 던짐)인가 */
    static boolean isStub(String body) {
        return STUB.matcher(COMMENTS.matcher(body).replaceAll("").strip()).matches();
    }

    private static String body(String s, int open) {
        int depth = 1;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open, i);
        }
        return null;
    }
}
