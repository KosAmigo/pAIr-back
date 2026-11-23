package com.pair.domain.taskgen.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 비개발 과제의 CSV 자료. 검산식(v(...))이 읽는 표와 칸 수 검사에 쓴다 */
final class BizTables implements CalcParser.Tables {

    private final Map<String, List<List<String>>> tables = new LinkedHashMap<>();

    BizTables(List<DevDraft.FileDraft> csvFiles) {
        for (var f : csvFiles == null ? List.<DevDraft.FileDraft>of() : csvFiles) {
            if (f != null && f.path() != null && f.content() != null) tables.put(f.path(), parseCsv(f.content()));
        }
    }

    Map<String, List<List<String>>> all() {
        return tables;
    }

    /** 쉼표 구분, 큰따옴표 안의 쉼표·줄바꿈 허용. 빈 줄은 건너뛴다 */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String t = text.strip().replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < t.length() && t.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        row.add(cell.toString());
        if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
        return rows;
    }

    /** "8,430", "34%", "62000명" 같은 칸을 숫자로. 숫자가 아니면 null */
    static Double num(String s) {
        if (s == null) return null;
        String v = s.replaceAll("[,\\s원%명건개]", "");
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public double value(String path, String where, String column) {
        List<List<String>> rows = tables.get(path);
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("없는 CSV 입니다: " + path);
        List<String> head = rows.get(0).stream().map(String::strip).toList();
        List<int[]> idxAndVal = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (String cond : where.split(";")) {
            if (cond.isBlank()) continue;
            String[] kv = cond.split("=", 2);
            if (kv.length != 2) throw new IllegalArgumentException("조건 형식이 올바르지 않습니다: " + cond);
            int k = head.indexOf(kv[0].strip());
            if (k < 0) throw new IllegalArgumentException(path + " 에 없는 열입니다: " + kv[0].strip());
            idxAndVal.add(new int[] {k});
            vals.add(kv[1].strip());
        }
        int col = head.indexOf(column.strip());
        if (col < 0) throw new IllegalArgumentException(path + " 에 없는 열입니다: " + column.strip());
        List<String> hit = null;
        int count = 0;
        for (List<String> r : rows.subList(1, rows.size())) {
            boolean ok = true;
            for (int i = 0; i < idxAndVal.size(); i++) {
                int k = idxAndVal.get(i)[0];
                if (k >= r.size() || !r.get(k).strip().equals(vals.get(i))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                count++;
                hit = r;
            }
        }
        if (count != 1) throw new IllegalArgumentException(path + " " + where + ": 조건에 맞는 행이 " + count + "개입니다 (1개여야 함)");
        Double n = col < hit.size() ? num(hit.get(col)) : null;
        if (n == null) throw new IllegalArgumentException(path + " " + where + ": '" + column + "' 칸이 숫자가 아닙니다");
        return n;
    }
}
