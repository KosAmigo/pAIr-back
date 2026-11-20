package com.pair.question;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단답형 답 비교 (AI 없이 코드로만).
 * 정리: 공백(앞뒤·중간)과 쉼표를 없애고 전각 숫자를 반각으로 바꾼다.
 * 인정 답안이 숫자면 응시자 답의 맨 앞 숫자를 읽어 값을 비교한다(뒤의 "만 원", "%" 같은 단위 글자는 무시).
 * 숫자가 아니면 대소문자를 무시하고 문자열을 비교한다. 인정 답안 중 하나라도 맞으면 정답.
 */
public final class ShortAnswerMatcher {

    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");
    private static final BigDecimal TOLERANCE = new BigDecimal("1e-9");

    private ShortAnswerMatcher() {}

    public static boolean matches(String answer, List<String> accepted) {
        if (answer == null) return false;
        String a = normalize(answer);
        if (a.isEmpty()) return false;
        BigDecimal got = leadingNumber(a);
        for (String key : accepted) {
            String k = normalize(key);
            if (k.isEmpty()) continue;
            if (NUMBER.matcher(k).matches()) {
                if (got != null && got.subtract(toNumber(k)).abs().compareTo(TOLERANCE) <= 0) return true;
            } else if (a.equalsIgnoreCase(k)) {
                return true;
            }
        }
        return false;
    }

    /** 공백·쉼표 제거, 전각 숫자·부호·점을 반각으로 */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == ',' || c == '，' || Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            else if (c == '．') c = '.';
            else if (c == '－' || c == '−') c = '-';
            else if (c == '＋') c = '+';
            sb.append(c);
        }
        return sb.toString();
    }

    private static BigDecimal leadingNumber(String normalized) {
        Matcher m = LEADING_NUMBER.matcher(normalized);
        return m.find() ? toNumber(m.group()) : null;
    }

    private static BigDecimal toNumber(String t) {
        if (t.startsWith("+")) t = t.substring(1);
        if (t.endsWith(".")) t = t + "0";
        return new BigDecimal(t);
    }
}
