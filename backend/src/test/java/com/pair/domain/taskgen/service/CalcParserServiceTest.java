package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검산 파서: 허용된 식만 계산하고, 그 밖은 오류 */
class CalcParserServiceTest {

    private static final BizTableService TABLES = new BizTableService(List.of(new DevDraftDto.FileDraft("자료/매출.csv",
            "월,요금제,가입자 수,월 요금(원)\n2026-10,베이직,\"62,000\",9900\n2026-10,라이트,0,5900\n2026-09,베이직,60000,9900\n2026-09,베이직,1,1\n")));

    private static double eval(String expr) {
        return CalcParserService.eval(expr, TABLES);
    }

    @Test
    @DisplayName("사칙연산, 괄호, 앞의 -, 연산 순서")
    void arithmetic() {
        assertThat(eval("1 + 2 * 3")).isEqualTo(7);
        assertThat(eval("(1 + 2) * 3")).isEqualTo(9);
        assertThat(eval("-4 / 8 + 1")).isEqualTo(0.5);
        assertThat(eval("10 - 2 - 3")).isEqualTo(5);
        assertThat(eval("2 * -3")).isEqualTo(-6);
    }

    @Test
    @DisplayName("v(...) 는 조건에 맞는 한 행의 칸 값 (쉼표가 든 숫자도 읽는다)")
    void lookup() {
        assertThat(eval("v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"가입자 수\")")).isEqualTo(62000);
        assertThat(eval("v(\"자료/매출.csv\",\"월=2026-10;요금제=베이직\",\"가입자 수\") * 0.34 * (9900 - 5900) / 10000")).isCloseTo(8432, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("round 는 반올림(0.5 는 올림), 자릿수는 생략하면 0")
    void rounding() {
        assertThat(eval("round(2.5)")).isEqualTo(3);
        assertThat(eval("round(1.005 * 1000, 0)")).isEqualTo(1005);
        assertThat(eval("round(3.14159, 2)")).isEqualTo(3.14);
        assertThat(eval("round(1234, -2)")).isEqualTo(1200);
        assertThatThrownBy(() -> eval("round(1.5, 1.5)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("허용 밖 함수·글자·문법은 오류")
    void rejected() {
        for (String bad : List.of("sqrt(4)", "Math.abs(1)", "System.exit(0)", "eval(\"1\")", "abs(1)", "1 +", "(1 + 2", "1 2",
                "v(\"자료/매출.csv\", \"월=2026-10\")", "round()", "x", "1 ; 2", "'a'", "", "  ", "1 / 0")) {
            assertThatThrownBy(() -> eval(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> eval("pow(2, 3)")).hasMessageContaining("허용되지 않은 함수");
    }

    @Test
    @DisplayName("조건에 맞는 행이 0개나 2개 이상이면 오류, 없는 CSV·열, 숫자가 아닌 칸도 오류")
    void lookupErrors() {
        assertThatThrownBy(() -> eval("v(\"자료/매출.csv\", \"월=2026-12;요금제=베이직\", \"가입자 수\")")).hasMessageContaining("0개");
        assertThatThrownBy(() -> eval("v(\"자료/매출.csv\", \"월=2026-09;요금제=베이직\", \"가입자 수\")")).hasMessageContaining("2개");
        assertThatThrownBy(() -> eval("v(\"자료/없음.csv\", \"월=2026-10\", \"가입자 수\")")).hasMessageContaining("없는 CSV");
        assertThatThrownBy(() -> eval("v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"없는 열\")")).hasMessageContaining("없는 열");
        assertThatThrownBy(() -> eval("v(\"자료/매출.csv\", \"월=2026-10;요금제=베이직\", \"요금제\")")).hasMessageContaining("숫자가 아닙니다");
    }

    @Test
    @DisplayName("CSV 읽기: 따옴표 안의 쉼표, 빈 줄, 숫자 칸의 단위")
    void csv() {
        var rows = BizTableService.parseCsv("a,b\n\"1,5\",\"x\"\"y\"\n\n3,4\n");
        assertThat(rows).containsExactly(List.of("a", "b"), List.of("1,5", "x\"y"), List.of("3", "4"));
        assertThat(BizTableService.num("8,430원")).isEqualTo(8430);
        assertThat(BizTableService.num("34%")).isEqualTo(34);
        assertThat(BizTableService.num("abc")).isNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("g5 04·10: 따옴표 밖의 −, ×, ÷ 는 -, *, / 로 읽고 따옴표 안의 글자는 그대로 둔다")
    void unicodeOperators() {
        assertThat(eval("10 − 4 ÷ 2 × 3")).isEqualTo(4);
        assertThat(CalcParserService.asciiOperators("v(\"a−b.csv\", \"x=1\", \"y\") − 1")).isEqualTo("v(\"a−b.csv\", \"x=1\", \"y\") - 1");
    }
}
