package com.pair.domain.taskgen.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 값 비교: 단위를 맞춰 같은 값인지 본다(0.5 와 50퍼센트, 1,050 과 1050원). 모델을 부르지 않는다.
 * 숫자가 퍼센트로 적혔으면 100 으로 나눈 값도 같은 값으로 본다.
 */
final class ValueService {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern UNIT = Pattern.compile("(퍼센트포인트|퍼센트|%|원|건|명|개|배|회|일|시간|분|점)");

    private ValueService() {}

    /** 글에 든 숫자들(쉼표 없앰). 퍼센트로 적힌 글이면 100 으로 나눈 값도 넣는다 */
    static Set<BigDecimal> values(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (text == null) return out;
        boolean percent = text.contains("%") || text.contains("퍼센트");
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            try {
                BigDecimal v = new BigDecimal(m.group().replace(",", "")).stripTrailingZeros();
                out.add(v);
                if (percent) out.add(v.movePointLeft(2).stripTrailingZeros());
            } catch (NumberFormatException ignored) {
                // 숫자가 아니면 건너뛴다
            }
        }
        return out;
    }

    /** 두 글이 같은 값을 말하는가(단위를 맞춰, 숫자 하나라도 겹치면) */
    static boolean same(String a, String b) {
        Set<BigDecimal> x = values(a);
        x.retainAll(values(b));
        return !x.isEmpty();
    }

    /** 숫자가 너무 작아(0~9 정수) 우연히 겹치기 쉬운가 */
    static boolean trivial(BigDecimal v) {
        return v.abs().compareTo(BigDecimal.TEN) < 0 && v.scale() <= 0;
    }

    /** 정답 키 값에서 단위 글자와 쉼표를 떼 숫자만 남긴다("1,050건" → "1050", "25%" → "25"). 숫자가 하나가 아니면 그대로 */
    static String plain(String text) {
        if (text == null) return null;
        String t = UNIT.matcher(text.strip()).replaceAll("").replace(",", "").strip();
        return t.matches("-?\\d+(\\.\\d+)?") ? t : text.strip();
    }
}
