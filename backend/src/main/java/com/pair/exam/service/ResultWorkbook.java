package com.pair.exam.service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.pair.exam.domain.ExamSession;
import com.pair.exam.domain.ProctorEvent;

/** 회차 결과 엑셀: 요약(지표·점수 분포 차트·항목 평균) + 응시자별 결과표. 공문서처럼 보이도록 띠·표·조건부 서식을 쓴다. */
final class ResultWorkbook {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    private static final int TABLE_HEADER_ROW = 3;

    private ResultWorkbook() {}

    static byte[] build(SessionResultService.Result r) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XlsxStyles st = new XlsxStyles(wb);
            summary(wb, st, r);
            table(wb, st, r);
            wb.getProperties().getCoreProperties().setTitle("pAIr 회차 결과 - " + r.title());
            wb.getProperties().getCoreProperties().setCreator("pAIr");
            return ExamWorkbooks.toBytes(wb);
        } catch (IOException e) {
            throw new IllegalStateException("결과 파일을 만들지 못했습니다.", e);
        }
    }

    // ── 요약 시트 ──

    private static void summary(XSSFWorkbook wb, XlsxStyles st, SessionResultService.Result r) {
        XSSFSheet sh = wb.createSheet("요약");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyles.BRAND);
        ExamWorkbooks.widths(sh, 16, 16, 16, 16, 16, 16);

        XSSFCellStyle title = st.style(st.font(20, true, XlsxStyles.WHITE, false), XlsxStyles.BRAND, HorizontalAlignment.LEFT, false, false);
        title.setIndention((short) 1);
        st.band(sh, 0, 5, "pAIr   회차 결과 보고서", title, 44);
        XSSFCellStyle sub = st.style(st.font(12, true, XlsxStyles.BRAND_DARK, false), XlsxStyles.BRAND_SOFT, HorizontalAlignment.LEFT, false, false);
        sub.setIndention((short) 1);
        st.band(sh, 1, 5, r.title() + "  ·  " + phase(r.phase()) + "  ·  출력 " + STAMP.format(Instant.now()) + " (KST)", sub, 30);
        sh.createRow(2).setHeightInPoints(10);

        // 지표 카드: 라벨 줄 + 숫자 줄
        String[] labels = {"대상자", "응시 시작", "제출", "채점 완료", "평균 점수", "감독 이벤트 합계"};
        Object[] values = {r.participants(), r.started(), r.submitted(), r.scored(), r.average() == null ? "-" : r.average(), totalEvents(r)};
        XSSFCellStyle label = st.style(st.font(10, true, XlsxStyles.MUTED, false), XlsxStyles.ZEBRA, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle big = st.style(st.font(22, true, XlsxStyles.BRAND, false), XlsxStyles.WHITE, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle bigNum = st.withFormat(big, st.font(22, true, XlsxStyles.BRAND, false), null, "0.#");
        Row lr = sh.createRow(3);
        lr.setHeightInPoints(22);
        Row vr = sh.createRow(4);
        vr.setHeightInPoints(42);
        for (int c = 0; c < labels.length; c++) {
            Cell l = lr.createCell(c);
            l.setCellValue(labels[c]);
            l.setCellStyle(label);
            Cell v = vr.createCell(c);
            if (values[c] instanceof Number n) {
                v.setCellValue(n.doubleValue());
                v.setCellStyle(bigNum);
            } else {
                v.setCellValue(String.valueOf(values[c]));
                v.setCellStyle(big);
            }
        }
        sh.createRow(5).setHeightInPoints(14);

        int row = 6;
        row = distribution(sh, st, r, row);
        row = itemAverages(sh, st, r, row + 1);
        events(sh, st, r, row + 1);
        ExamWorkbooks.footerSetup(sh, 0);
    }

    private static long totalEvents(SessionResultService.Result r) {
        return r.eventTotals().values().stream().mapToLong(Long::longValue).sum();
    }

    private static void sectionTitle(XSSFSheet sh, XlsxStyles st, int row, String text) {
        XSSFCellStyle s = st.style(st.font(13, true, XlsxStyles.INK, false), null, HorizontalAlignment.LEFT, false, false);
        Row r = sh.createRow(row);
        r.setHeightInPoints(26);
        Cell c = r.createCell(0);
        c.setCellValue("■ " + text);
        c.setCellStyle(s);
    }

    private static XSSFCellStyle tableHead(XlsxStyles st) {
        return st.style(st.font(11, true, XlsxStyles.WHITE, false), XlsxStyles.INK, HorizontalAlignment.CENTER, true, false);
    }

    private static int distribution(XSSFSheet sh, XlsxStyles st, SessionResultService.Result r, int start) {
        sectionTitle(sh, st, start, "점수 분포");
        XSSFCellStyle head = tableHead(st);
        XSSFCellStyle cell = st.style(st.font(11, false, XlsxStyles.INK, false), XlsxStyles.WHITE, HorizontalAlignment.CENTER, true, false);
        Row h = sh.createRow(start + 1);
        text(h, 0, "점수 구간", head);
        text(h, 1, "인원", head);
        int first = start + 2;
        int i = 0;
        for (SessionResultService.Bucket b : r.distribution()) {
            Row row = sh.createRow(first + i++);
            text(row, 0, b.from() + " ~ " + b.to(), cell);
            num(row, 1, b.count(), cell);
        }
        int last = first + i - 1;
        if (i > 0) barChart(sh, first, last, start + 1);
        return Math.max(last, start + 14) + 1;
    }

    /** 점수 분포 막대그래프 (표 오른쪽) */
    private static void barChart(XSSFSheet sh, int first, int last, int top) {
        XSSFDrawing drawing = sh.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 2, top, 6, top + 14);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("점수 분포");
        chart.setTitleOverlay(false);
        XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
        left.setMinimum(0);
        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(sh, new CellRangeAddress(first, last, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(sh, new CellRangeAddress(first, last, 1, 1));
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottom, left);
        data.setBarDirection(BarDirection.COL);
        XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(cats, vals);
        series.setTitle("인원", null);
        series.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(new byte[] {(byte) 0x6C, (byte) 0x47, (byte) 0xFF})));
        chart.plot(data);
    }

    private static int itemAverages(XSSFSheet sh, XlsxStyles st, SessionResultService.Result r, int start) {
        if (r.itemAverages().isEmpty()) return start;
        sectionTitle(sh, st, start, "채점 항목별 평균");
        XSSFCellStyle head = tableHead(st);
        XSSFCellStyle key = st.style(st.font(11, false, XlsxStyles.INK, false), XlsxStyles.WHITE, HorizontalAlignment.LEFT, true, false);
        XSSFCellStyle num = st.withFormat(st.style(st.font(11, false, XlsxStyles.INK, false), XlsxStyles.WHITE, HorizontalAlignment.CENTER, true, false),
                st.font(11, false, XlsxStyles.INK, false), null, "0.0");
        Row h = sh.createRow(start + 1);
        text(h, 0, "항목", head);
        text(h, 1, "전체 평균", head);
        text(h, 2, "상위 25%", head);
        text(h, 3, "하위 25%", head);
        int row = start + 2;
        for (Map.Entry<String, Double> e : r.itemAverages().entrySet()) {
            Row x = sh.createRow(row++);
            text(x, 0, e.getKey(), key);
            num(x, 1, e.getValue(), num);
            optional(x, 2, r.topItemAverages().get(e.getKey()), num);
            optional(x, 3, r.bottomItemAverages().get(e.getKey()), num);
        }
        return row;
    }

    private static int events(XSSFSheet sh, XlsxStyles st, SessionResultService.Result r, int start) {
        if (r.eventTotals().isEmpty()) return start;
        sectionTitle(sh, st, start, "감독 이벤트 합계");
        XSSFCellStyle head = tableHead(st);
        XSSFCellStyle key = st.style(st.font(11, false, XlsxStyles.INK, false), XlsxStyles.WHITE, HorizontalAlignment.LEFT, true, false);
        XSSFCellStyle num = st.style(st.font(11, false, XlsxStyles.INK, false), XlsxStyles.WHITE, HorizontalAlignment.CENTER, true, false);
        Row h = sh.createRow(start + 1);
        text(h, 0, "유형", head);
        text(h, 1, "건수", head);
        int row = start + 2;
        for (Map.Entry<ProctorEvent.Type, Long> e : r.eventTotals().entrySet()) {
            Row x = sh.createRow(row++);
            text(x, 0, e.getKey().name(), key);
            num(x, 1, e.getValue(), num);
        }
        return row;
    }

    // ── 응시자 결과 시트 ──

    private static void table(XSSFWorkbook wb, XlsxStyles st, SessionResultService.Result r) {
        XSSFSheet sh = wb.createSheet("응시자 결과");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyles.INK);

        List<String> itemKeys = new ArrayList<>(r.itemAverages().keySet());
        List<String> head = new ArrayList<>(List.of("순위", "이름", "이메일", "상태", "입장", "시작", "제출", "총점", "만점"));
        head.addAll(itemKeys);
        ProctorEvent.Type[] types = ProctorEvent.Type.values();
        for (ProctorEvent.Type t : types) head.add("감독:" + t.name());
        int lastCol = head.size() - 1;

        XSSFCellStyle title = st.style(st.font(18, true, XlsxStyles.WHITE, false), XlsxStyles.BRAND, HorizontalAlignment.LEFT, false, false);
        title.setIndention((short) 1);
        st.band(sh, 0, lastCol, "pAIr   응시자별 결과  ·  " + r.title(), title, 40);
        XSSFCellStyle sub = st.style(st.font(10, false, XlsxStyles.BRAND_DARK, false), XlsxStyles.BRAND_SOFT, HorizontalAlignment.LEFT, false, false);
        sub.setIndention((short) 1);
        st.band(sh, 1, lastCol, "대상자 " + r.participants() + "명  ·  제출 " + r.submitted() + "명  ·  채점 완료 " + r.scored() + "명  ·  감독 이벤트는 참고 자료이며 부정행위를 확정하지 않습니다.", sub, 24);
        sh.createRow(2).setHeightInPoints(8);

        XSSFCellStyle headStyle = tableHead(st);
        Row hr = sh.createRow(TABLE_HEADER_ROW);
        hr.setHeightInPoints(30);
        for (int c = 0; c < head.size(); c++) text(hr, c, head.get(c), headStyle);

        XSSFCellStyle[] left = {body(st, XlsxStyles.WHITE, HorizontalAlignment.LEFT, null), body(st, XlsxStyles.ZEBRA, HorizontalAlignment.LEFT, null)};
        XSSFCellStyle[] center = {body(st, XlsxStyles.WHITE, HorizontalAlignment.CENTER, null), body(st, XlsxStyles.ZEBRA, HorizontalAlignment.CENTER, null)};
        XSSFCellStyle[] number = {body(st, XlsxStyles.WHITE, HorizontalAlignment.CENTER, "0.##"), body(st, XlsxStyles.ZEBRA, HorizontalAlignment.CENTER, "0.##")};

        int i = TABLE_HEADER_ROW + 1;
        for (SessionResultService.Row row : r.rows()) {
            int z = (i - TABLE_HEADER_ROW) % 2 == 0 ? 1 : 0;
            Row x = sh.createRow(i++);
            x.setHeightInPoints(22);
            int c = 0;
            if (row.rank() == 0) text(x, c++, "", center[z]);
            else num(x, c++, row.rank(), center[z]);
            text(x, c++, row.name(), left[z]);
            text(x, c++, row.email(), left[z]);
            text(x, c++, state(row.state()), center[z]);
            text(x, c++, when(row.enteredAt()), center[z]);
            text(x, c++, when(row.startedAt()), center[z]);
            text(x, c++, when(row.submittedAt()), center[z]);
            optional(x, c++, row.score(), number[z]);
            optional(x, c++, row.maxScore(), number[z]);
            for (String k : itemKeys) optional(x, c++, row.items().get(k), number[z]);
            for (ProctorEvent.Type t : types) num(x, c++, row.events().getOrDefault(t, 0L), center[z]);
        }

        int lastRow = Math.max(i - 1, TABLE_HEADER_ROW + 1);
        conditional(sh, lastRow, 3, 7, 9 + itemKeys.size(), lastCol);
        int[] fixed = {7, 14, 30, 11, 19, 19, 19, 9, 9};
        for (int c = 0; c < head.size(); c++) sh.setColumnWidth(c, (c < fixed.length ? fixed[c] : 14) * 256 + 512);
        sh.createFreezePane(3, TABLE_HEADER_ROW + 1);
        sh.setAutoFilter(new CellRangeAddress(TABLE_HEADER_ROW, lastRow, 0, lastCol));
        ExamWorkbooks.footerSetup(sh, TABLE_HEADER_ROW);
    }

    private static XSSFCellStyle body(XlsxStyles st, org.apache.poi.xssf.usermodel.XSSFColor fill, HorizontalAlignment h, String format) {
        XSSFCellStyle base = st.style(st.font(11, false, XlsxStyles.INK, false), fill, h, true, false);
        return format == null ? base : st.withFormat(base, st.font(11, false, XlsxStyles.INK, false), null, format);
    }

    /** 상태(제출 초록, 미입장 회색, 응시 중 파랑)·총점 색 단계·감독 이벤트가 있는 칸 강조 */
    private static void conditional(XSSFSheet sh, int lastRow, int stateCol, int scoreCol, int eventStartCol, int lastCol) {
        int first = TABLE_HEADER_ROW + 1;
        SheetConditionalFormatting cf = sh.getSheetConditionalFormatting();
        String[][] states = {{"제출", "S"}, {"미입장", "G"}, {"응시 중", "B"}, {"대기", "B"}};
        for (String[] s : states) {
            ConditionalFormattingRule rule = cf.createConditionalFormattingRule(ComparisonOperator.EQUAL, "\"" + s[0] + "\"");
            rule.createPatternFormatting().setFillBackgroundColor(switch (s[1]) {
                case "S" -> XlsxStyles.SIGNAL_SOFT;
                case "G" -> XlsxStyles.GRAY_SOFT;
                default -> XlsxStyles.BLUE_SOFT;
            });
            if ("S".equals(s[1])) rule.createFontFormatting().setFontColor(XlsxStyles.SIGNAL);
            cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(first, lastRow, stateCol, stateCol)}, rule);
        }
        ConditionalFormattingRule scoreHigh = cf.createConditionalFormattingRule(ComparisonOperator.GE, "80");
        org.apache.poi.ss.usermodel.FontFormatting highFont = scoreHigh.createFontFormatting();
        highFont.setFontColor(XlsxStyles.SIGNAL);
        highFont.setFontStyle(false, true);
        cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(first, lastRow, scoreCol, scoreCol)}, scoreHigh);
        ConditionalFormattingRule scoreLow = cf.createConditionalFormattingRule(ComparisonOperator.LT, "50");
        scoreLow.createFontFormatting().setFontColor(XlsxStyles.ALERT);
        cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(first, lastRow, scoreCol, scoreCol)}, scoreLow);
        if (eventStartCol <= lastCol) {
            ConditionalFormattingRule ev = cf.createConditionalFormattingRule(ComparisonOperator.GT, "0");
            ev.createPatternFormatting().setFillBackgroundColor(XlsxStyles.CAUTION_SOFT);
            ev.createFontFormatting().setFontColor(XlsxStyles.ALERT);
            cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(first, lastRow, eventStartCol, lastCol)}, ev);
        }
    }

    // ── 도우미 ──

    private static String state(SessionResultService.State s) {
        return switch (s) {
            case NOT_ENTERED -> "미입장";
            case WAITING -> "대기";
            case SOLVING -> "응시 중";
            case SUBMITTED -> "제출";
        };
    }

    private static String phase(ExamSession.Phase p) {
        return switch (p) {
            case UPCOMING -> "시작 전";
            case LIVE -> "진행 중";
            case ENDED -> "종료";
            case CANCELED -> "취소";
        };
    }

    private static String when(Instant t) {
        return t == null ? "" : WHEN.format(t);
    }

    /** 문자열 셀로만 넣으므로 엑셀이 수식으로 해석하지 않는다 */
    private static void text(Row row, int col, String v, XSSFCellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void num(Row row, int col, Number v, XSSFCellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v.doubleValue());
        c.setCellStyle(style);
    }

    private static void optional(Row row, int col, Number v, XSSFCellStyle style) {
        if (v == null) row.createCell(col).setCellStyle(style);
        else num(row, col, v, style);
    }
}
