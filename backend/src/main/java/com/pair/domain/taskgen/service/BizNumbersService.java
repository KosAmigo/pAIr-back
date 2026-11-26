package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BIZ 숫자표(M1). 숫자는 설계도의 tables·figures 에만 정하고, CSV 는 표에서 만들고, 문서 속 숫자는 표와 같아야 한다.
 * 모두 코드로 확인한다: 숫자표 정합성(부분 ≤ 전체, 합계), 사실 목록의 숫자가 숫자표에 있음, 문서의 숫자가 숫자표에 있음.
 */
final class BizNumbersService {

    static final String NAME = "자료 숫자가 숫자표와 같음"; // M1

    /** 천 단위 쉼표 숫자(앞이 숫자·쉼표가 아니고 뒤가 숫자가 아닐 때만)나 보통 숫자 */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d,])\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?![\\d]|,\\d)|\\d+(?:\\.\\d+)?");
    private static final Pattern PLAIN = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 공백 없는 칸이 쉼표로 이어진 줄(CSV 행): 칸마다 읽는다("1000,100,5000000"은 세 수) */
    private static final Pattern CSV_ROW = Pattern.compile("^\\s*[^\\s,]+(,[^\\s,]+){2,}\\s*$");
    /** 날짜·쪽·차례·시각·사람 수·조항·팀 수 같은 숫자는 수치가 아니다 */
    private static final Pattern NOT_A_FIGURE = Pattern.compile("^\\s*(월|일|년|분기|쪽|페이지|번째|차\\b|시|명|개|조|호|팀|곳|가지)");
    private static final int SHOWN = 5;

    private BizNumbersService() {}

    /** 찾은 숫자: canon 은 정리한 값, after 는 그 뒤 같은 칸·줄의 글 */
    private record Hit(String raw, String canon, String after) {}

    private static List<Hit> scan(String text) {
        List<Hit> out = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\\R")) {
            if (CSV_ROW.matcher(line).matches()) {
                for (String cell : line.split(",")) {
                    Matcher m = PLAIN.matcher(cell);
                    while (m.find()) out.add(new Hit(m.group(), canon(m.group()), cell.substring(m.end())));
                }
            } else {
                Matcher m = NUMBER.matcher(line);
                while (m.find()) out.add(new Hit(m.group(), canon(m.group()), line.substring(m.end())));
            }
        }
        return out;
    }

    /** 글 속 숫자(쉼표·끝자리 0 정리). "1,050건" → "1050", "2.50" → "2.5". CSV 줄은 칸마다 읽는다 */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        scan(text).forEach(h -> out.add(h.canon()));
        return out;
    }

    private static String canon(String n) {
        return new BigDecimal(n.replace(",", "")).stripTrailingZeros().toPlainString();
    }

    private static List<BlueprintDto.Table> tables(BlueprintDto b) {
        return b.tables() == null ? List.of() : b.tables();
    }

    /** 표 칸의 모든 숫자 */
    static Set<String> tableNumbers(BlueprintDto b) {
        Set<String> out = new LinkedHashSet<>();
        for (var t : tables(b)) for (var row : t.rows() == null ? List.<List<String>>of() : t.rows()) for (String c : row) out.addAll(numbers(c));
        return out;
    }

    private static Set<String> figureNumbers(BlueprintDto b) {
        Set<String> out = new LinkedHashSet<>();
        for (var f : b.figures() == null ? List.<BlueprintDto.Figure>of() : b.figures()) out.addAll(numbers(f.value()));
        return out;
    }

    /** 사실 목록 문장 속 숫자 */
    static Set<String> factNumbers(BlueprintDto b) {
        Set<String> out = new LinkedHashSet<>();
        for (var f : b.facts() == null ? List.<BlueprintDto.Fact>of() : b.facts()) out.addAll(numbers(f.text()));
        return out;
    }

    /** 설계도 단계 확인: 숫자표 정합성과 사실 목록의 숫자가 숫자표에 있는지. 문제 문장 목록(없으면 빈 목록) */
    static List<String> blueprintProblems(BlueprintDto b) {
        List<String> bad = new ArrayList<>();
        Map<String, Double> byLabel = new LinkedHashMap<>();
        for (var f : b.figures() == null ? List.<BlueprintDto.Figure>of() : b.figures()) {
            Double v = BizTableService.num(f.value());
            if (f.label() == null || f.label().isBlank() || v == null) bad.add("숫자표 항목 '" + f.label() + "' 의 값이 숫자가 아닙니다: " + f.value());
            else if (byLabel.put(f.label().strip(), v) != null) bad.add("숫자표 항목 이름이 겹칩니다: " + f.label());
        }
        for (var f : b.figures() == null ? List.<BlueprintDto.Figure>of() : b.figures()) {
            Double v = byLabel.get(f.label() == null ? "" : f.label().strip());
            if (v == null) continue;
            if (f.partOf() != null && !f.partOf().isBlank()) {
                Double whole = byLabel.get(f.partOf().strip());
                if (whole == null) bad.add("'" + f.label() + "' 의 전체(partOf) '" + f.partOf() + "' 가 숫자표에 없습니다");
                else if (v > whole) bad.add("부분이 전체보다 큽니다: " + f.label() + " " + f.value() + " > " + f.partOf() + " " + trim(whole));
            }
            if (f.sumOf() != null && !f.sumOf().isEmpty()) {
                double sum = 0;
                boolean known = true;
                for (String part : f.sumOf()) {
                    Double p = byLabel.get(part == null ? "" : part.strip());
                    if (p == null) {
                        bad.add("'" + f.label() + "' 합계의 항목 '" + part + "' 가 숫자표에 없습니다");
                        known = false;
                    } else sum += p;
                }
                if (known && Math.abs(sum - v) > 1e-6 * Math.max(1, Math.abs(v))) bad.add("합계가 맞지 않습니다: " + f.label() + " " + f.value() + " ≠ 항목 합 " + trim(sum));
            }
        }
        Set<String> known = new LinkedHashSet<>(tableNumbers(b));
        known.addAll(figureNumbers(b));
        List<String> unknown = factNumbers(b).stream().filter(n -> !known.contains(n)).toList();
        if (!unknown.isEmpty()) bad.add("사실 목록의 숫자가 숫자표(tables·figures)에 없습니다: " + unknown + ". 숫자는 숫자표에 먼저 정하세요");
        return bad;
    }

    /** 숫자표의 표를 CSV 파일로(쉼표·따옴표가 든 칸은 따옴표로 감싼다) */
    static List<DevDraftDto.FileDraft> csvFiles(BlueprintDto b) {
        List<DevDraftDto.FileDraft> out = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        for (var t : tables(b)) {
            if (!paths.add(String.valueOf(t.path()))) continue; // 같은 경로는 한 번만(저장 때 경로가 겹치면 DB 오류). 중복은 설계도 확인이 따로 잡는다
            StringBuilder sb = new StringBuilder(line(t.columns())).append('\n');
            for (var row : t.rows() == null ? List.<List<String>>of() : t.rows()) sb.append(line(row)).append('\n');
            out.add(new DevDraftDto.FileDraft(t.path(), sb.toString()));
        }
        return out;
    }

    private static String line(List<String> cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells == null ? List.<String>of() : cells) {
            String v = c == null ? "" : c.strip();
            out.add(v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
        }
        return String.join(",", out);
    }

    /** 문서(쪽별 글) 속 숫자가 모두 숫자표나 사실 목록(이미 숫자표에 있음)에 있는지. 날짜·쪽·차례 숫자는 뺀다 */
    static GenCheckResultDto documentsCheck(BlueprintDto b, List<BizDraftDto.DocDraft> docs) {
        Set<String> allowed = new LinkedHashSet<>(tableNumbers(b));
        allowed.addAll(factNumbers(b));
        List<String> bad = new ArrayList<>();
        for (var d : docs == null ? List.<BizDraftDto.DocDraft>of() : docs) {
            for (String page : d.pages() == null ? List.<String>of() : d.pages()) {
                for (Hit h : scan(page)) {
                    if (NOT_A_FIGURE.matcher(h.after()).find() || allowed.contains(h.canon())) continue;
                    String at = h.raw() + "(" + d.path() + ")";
                    if (!bad.contains(at)) bad.add(at);
                }
            }
        }
        if (bad.isEmpty()) return new GenCheckResultDto(NAME, true, "문서의 숫자가 모두 숫자표와 같습니다.");
        return new GenCheckResultDto(NAME, false, "문서에 숫자표에 없는 숫자가 있습니다: " + String.join(", ", bad.subList(0, Math.min(SHOWN, bad.size())))
                + (bad.size() > SHOWN ? " 외 " + (bad.size() - SHOWN) + "개" : "") + ". 사실 목록의 숫자만 쓰세요.");
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
