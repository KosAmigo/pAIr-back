package com.pair.infra.xlsx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * 명단 엑셀(대상자·기업 회원)이 함께 쓰는 읽기·인쇄·저장 코드. 다른 부분(열 이름, 한 행을 DTO로 바꾸기)은 호출하는 쪽이 넘긴다.
 */
public final class XlsxRosterService {

    private XlsxRosterService() {}

    /** 한 행을 DTO로 바꾼다. excelRow 는 엑셀 행 번호(1부터), values 는 찾은 열의 값(trim 됨) 순서 */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(int excelRow, List<String> values);
    }

    /** xlsx 는 ZIP 이라 "PK" 로 시작한다 */
    public static boolean isXlsx(byte[] bytes) {
        return bytes != null && bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    /**
     * 첫 시트의 앞 headerSearchRows 행에서 머리글 행을 찾고 그 아래를 읽는다.
     *
     * @param findColumns 한 행의 셀 값(빈 칸은 "")으로 열 위치들을 돌려준다. 머리글 행이 아니면 null
     * @param emailIndex 찾은 열 중 이메일의 순서. 예시 이메일 행을 건너뛰는 데 쓴다
     * @param maxRows 0 이하면 제한 없음
     */
    public static <T> List<T> readRows(byte[] bytes, int headerSearchRows, Function<List<String>, int[]> findColumns,
            String missingHeaderMessage, String sampleEmail, int emailIndex, int maxRows, String tooManyMessage,
            RowMapper<T> mapper) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int headerRow = -1;
            int[] cols = null;
            for (int i = 0; i <= Math.min(sheet.getLastRowNum(), headerSearchRows) && headerRow < 0; i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                List<String> cells = new ArrayList<>();
                for (Cell c : r) {
                    while (cells.size() < c.getColumnIndex()) cells.add("");
                    cells.add(fmt.formatCellValue(c));
                }
                int[] found = findColumns.apply(cells);
                if (found != null) {
                    headerRow = i;
                    cols = found;
                }
            }
            if (headerRow < 0) throw new IllegalArgumentException(missingHeaderMessage);

            List<T> rows = new ArrayList<>();
            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                List<String> values = new ArrayList<>();
                boolean allEmpty = true;
                for (int col : cols) {
                    String v = fmt.formatCellValue(r.getCell(col)).trim();
                    if (!v.isEmpty()) allEmpty = false;
                    values.add(v);
                }
                if (allEmpty) continue;
                if (sampleEmail.equalsIgnoreCase(values.get(emailIndex))) continue; // 지우지 않은 예시 행
                if (maxRows > 0 && rows.size() >= maxRows) throw new IllegalArgumentException(tooManyMessage);
                rows.add(mapper.map(i + 1, values));
            }
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("엑셀 파일을 읽지 못했습니다. 양식을 내려받아 써 주세요.");
        }
    }

    public static void widths(Sheet sh, int... chars) {
        for (int c = 0; c < chars.length; c++) sh.setColumnWidth(c, chars[c] * 256 + 512);
    }

    /**
     * 인쇄: 가로 한 쪽 너비에 맞춘다. headerLeft 가 null 이면 머리글은 비운다. repeatHeaderRow 가 0 이하면 반복 행을 두지 않는다.
     */
    public static void printSetup(Sheet sh, int repeatHeaderRow, String footerLeft, String headerLeft) {
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(true);
        ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 0);
        sh.setFitToPage(true);
        if (headerLeft != null) sh.getHeader().setLeft(headerLeft);
        sh.getFooter().setLeft(footerLeft);
        sh.getFooter().setRight("&P / &N");
        if (repeatHeaderRow > 0) sh.setRepeatingRows(new CellRangeAddress(repeatHeaderRow, repeatHeaderRow, -1, -1));
    }

    public static byte[] toBytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
