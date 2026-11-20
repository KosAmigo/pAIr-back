package com.pair.taskgen;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 단답형 검산식을 계산하는 작은 재귀 하강 파서. eval 이나 스크립트 엔진은 쓰지 않는다.
 * 받는 것: 숫자, + - * /, 괄호, 앞의 -, round(x) / round(x, 자릿수),
 * v("자료/파일.csv", "열=값;열=값", "열 이름") (조건에 맞는 한 행의 칸 값). 그 밖의 함수나 글자는 오류다.
 * round 는 반올림(0.5 는 올림)이다.
 */
public final class CalcParser {

    /** v(...) 가 읽는 표. 조건에 맞는 행이 정확히 1개가 아니거나 칸이 숫자가 아니면 IllegalArgumentException */
    public interface Tables {
        double value(String path, String where, String column);
    }

    private final String s;
    private final Tables tables;
    private int pos;

    private CalcParser(String s, Tables tables) {
        this.s = s;
        this.tables = tables;
    }

    public static double eval(String expr, Tables tables) {
        if (expr == null || expr.isBlank()) throw new IllegalArgumentException("계산식이 비어 있습니다.");
        CalcParser p = new CalcParser(expr, tables);
        double v = p.expr();
        p.skip();
        if (p.pos < p.s.length()) throw p.error("해석할 수 없는 부분입니다");
        return finite(v);
    }

    private double expr() {
        double v = term();
        while (true) {
            skip();
            if (eat('+')) v += term();
            else if (eat('-')) v -= term();
            else return v;
        }
    }

    private double term() {
        double v = unary();
        while (true) {
            skip();
            if (eat('*')) v *= unary();
            else if (eat('/')) {
                double d = unary();
                if (d == 0) throw error("0 으로 나눌 수 없습니다");
                v /= d;
            } else return v;
        }
    }

    private double unary() {
        skip();
        if (eat('-')) return -unary();
        return primary();
    }

    private double primary() {
        skip();
        if (pos >= s.length()) throw error("식이 끝났습니다");
        char c = s.charAt(pos);
        if (c == '(') {
            pos++;
            double v = expr();
            expect(')');
            return v;
        }
        if (Character.isDigit(c) || c == '.') return number();
        if (Character.isLetter(c) || c == '_') return call();
        throw error("허용되지 않은 글자 '" + c + "'");
    }

    private double number() {
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        try {
            return Double.parseDouble(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("숫자가 올바르지 않습니다: " + s.substring(start, pos));
        }
    }

    private double call() {
        int start = pos;
        while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
        String name = s.substring(start, pos);
        skip();
        if (!name.equals("v") && !name.equals("round")) throw error("허용되지 않은 함수입니다: " + name);
        expect('(');
        if (name.equals("v")) {
            String path = string();
            expect(',');
            String where = string();
            expect(',');
            String col = string();
            expect(')');
            return tables.value(path, where, col);
        }
        double x = expr();
        int digits = 0;
        skip();
        if (eat(',')) {
            double d = expr();
            if (d != Math.rint(d) || d < -6 || d > 6) throw error("round 의 자릿수는 -6~6 사이 정수여야 합니다");
            digits = (int) d;
        }
        expect(')');
        return new BigDecimal(Double.toString(finite(x))).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    private String string() {
        skip();
        if (!eat('"')) throw error("문자열(\"…\")이 와야 합니다");
        int end = s.indexOf('"', pos);
        if (end < 0) throw error("문자열이 닫히지 않았습니다");
        String v = s.substring(pos, end);
        pos = end + 1;
        return v;
    }

    private void expect(char c) {
        skip();
        if (!eat(c)) throw error("'" + c + "' 가 와야 합니다");
    }

    private boolean eat(char c) {
        if (pos < s.length() && s.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("계산식 오류: " + msg + " (위치 " + pos + ")");
    }

    private static double finite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("계산식 오류: 계산 결과가 올바르지 않습니다");
        return v;
    }
}
