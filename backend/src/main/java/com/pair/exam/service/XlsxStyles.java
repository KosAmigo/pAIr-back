package com.pair.exam.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide;

/** pAIr 엑셀 문서의 공통 색·글꼴·셀 서식. 템플릿과 결과표가 같은 모양을 쓴다. */
final class XlsxStyles {
    static final String FONT = "맑은 고딕";

    static final XSSFColor BRAND = rgb(0x6C, 0x47, 0xFF);
    static final XSSFColor BRAND_DARK = rgb(0x3B, 0x24, 0xB5);
    static final XSSFColor BRAND_SOFT = rgb(0xEE, 0xEA, 0xFF);
    static final XSSFColor INK = rgb(0x22, 0x26, 0x3A);
    static final XSSFColor MUTED = rgb(0x6B, 0x70, 0x85);
    static final XSSFColor LINE = rgb(0xD5, 0xD8, 0xE4);
    static final XSSFColor ZEBRA = rgb(0xF7, 0xF8, 0xFC);
    static final XSSFColor WHITE = rgb(0xFF, 0xFF, 0xFF);
    static final XSSFColor SIGNAL = rgb(0x16, 0xA3, 0x4A);
    static final XSSFColor SIGNAL_SOFT = rgb(0xDC, 0xF5, 0xE6);
    static final XSSFColor ALERT = rgb(0xDC, 0x26, 0x26);
    static final XSSFColor ALERT_SOFT = rgb(0xFD, 0xE4, 0xE4);
    static final XSSFColor CAUTION_SOFT = rgb(0xFF, 0xF1, 0xD6);
    static final XSSFColor BLUE_SOFT = rgb(0xDF, 0xEB, 0xFF);
    static final XSSFColor GRAY_SOFT = rgb(0xEC, 0xEE, 0xF3);

    private final XSSFWorkbook wb;

    XlsxStyles(XSSFWorkbook wb) {
        this.wb = wb;
    }

    static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[] {(byte) r, (byte) g, (byte) b});
    }

    XSSFFont font(int size, boolean bold, XSSFColor color, boolean italic) {
        XSSFFont f = wb.createFont();
        f.setFontName(FONT);
        f.setFontHeightInPoints((short) size);
        f.setBold(bold);
        f.setItalic(italic);
        f.setColor(color);
        return f;
    }

    /** fill 이 null 이면 채우지 않는다. border 가 true 면 연한 회색 테두리 */
    XSSFCellStyle style(XSSFFont font, XSSFColor fill, HorizontalAlignment h, boolean border, boolean wrap) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setAlignment(h);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(wrap);
        if (fill != null) {
            s.setFillForegroundColor(fill);
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (border) {
            for (BorderSide side : BorderSide.values()) {
                s.setBorderColor(side, LINE);
            }
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
        return s;
    }

    XSSFCellStyle withFormat(XSSFCellStyle base, XSSFFont font, XSSFColor fill, String format) {
        XSSFCellStyle s = wb.createCellStyle();
        s.cloneStyleFrom(base);
        s.setFont(font);
        if (fill != null) {
            s.setFillForegroundColor(fill);
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (format != null) s.setDataFormat(wb.createDataFormat().getFormat(format));
        return s;
    }

    /** 문서 맨 위 띠: 셀을 합치고 브랜드 색으로 채운다 */
    void band(Sheet sheet, int row, int lastCol, String text, XSSFCellStyle style, float height) {
        org.apache.poi.ss.usermodel.Row r = sheet.createRow(row);
        r.setHeightInPoints(height);
        for (int c = 0; c <= lastCol; c++) r.createCell(c).setCellStyle(style);
        r.getCell(0).setCellValue(text);
        if (lastCol > 0) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, lastCol));
    }
}
