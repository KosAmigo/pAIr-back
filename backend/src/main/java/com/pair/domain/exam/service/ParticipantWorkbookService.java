package com.pair.domain.exam.service;

import java.io.IOException;
import java.util.List;

import com.pair.infra.xlsx.XlsxRosterService;
import com.pair.infra.xlsx.XlsxStyleService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 플랫폼이 내려주는 엑셀: 대상자 등록 양식(만들기·읽기). 회차 결과표는 ResultWorkbookService. */
public final class ParticipantWorkbookService {

    /** 양식에 들어 있는 예시 행. 지우지 않고 올려도 대상자로 등록하지 않는다 */
    static final String SAMPLE_EMAIL = "hong@example.com";
    private static final int FORM_ROWS = 300;
    private static final int HEADER_SEARCH_ROWS = 15;
    private static final int COL_NO = 0;
    private static final int COL_NAME = 1;
    private static final int COL_EMAIL = 2;
    private static final int COL_NOTE = 3;
    private static final int LAST_COL = 3;

    private ParticipantWorkbookService() {}

    /** xlsx 는 ZIP 이라 "PK" 로 시작한다 */
    public static boolean isXlsx(byte[] bytes) {
        return XlsxRosterService.isXlsx(bytes);
    }

    public static byte[] results(SessionResultService.Result r) {
        return ResultWorkbookService.build(r);
    }

    // ── 대상자 양식 ──

    public static byte[] participantsTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XlsxStyleService st = new XlsxStyleService(wb);
            formSheet(wb, st);
            guideSheet(wb, st);
            wb.getProperties().getCoreProperties().setTitle("pAIr 응시 대상자 등록 양식");
            wb.getProperties().getCoreProperties().setCreator("pAIr");
            wb.getProperties().getExtendedProperties().getUnderlyingProperties().setCompany("pAIr");
            return XlsxRosterService.toBytes(wb);
        } catch (IOException e) {
            throw new IllegalStateException("양식을 만들지 못했습니다.", e);
        }
    }

    private static void formSheet(XSSFWorkbook wb, XlsxStyleService st) {
        XSSFSheet sh = wb.createSheet("대상자 등록");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyleService.BRAND);
        XlsxRosterService.widths(sh, 8, 20, 38, 30);

        XSSFCellStyle title = st.style(st.font(20, true, XlsxStyleService.WHITE, false), XlsxStyleService.BRAND, HorizontalAlignment.LEFT, false, false);
        title.setIndention((short) 1);
        st.band(sh, 0, LAST_COL, "pAIr   응시 대상자 등록 양식", title, 44);

        XSSFCellStyle sub = st.style(st.font(10, false, XlsxStyleService.BRAND_DARK, false), XlsxStyleService.BRAND_SOFT, HorizontalAlignment.LEFT, false, true);
        sub.setIndention((short) 1);
        st.band(sh, 1, LAST_COL, "이 파일은 pAIr 실전 시험 플랫폼에서 제공하는 양식입니다. 작성 후 회차 화면의 대상자 탭에서 '엑셀·CSV 올리기'로 제출하세요. "
                + "등록한 이메일로 사람마다 다른 입장 링크가 발송됩니다.", sub, 34);

        sh.createRow(2).setHeightInPoints(8);

        XSSFCellStyle head = st.style(st.font(11, true, XlsxStyleService.WHITE, false), XlsxStyleService.INK, HorizontalAlignment.CENTER, true, false);
        Row header = sh.createRow(3);
        header.setHeightInPoints(28);
        String[] names = {"번호", "이름 *", "이메일 *", "비고 (선택, 등록에 쓰지 않음)"};
        for (int c = 0; c <= LAST_COL; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(names[c]);
            cell.setCellStyle(head);
        }

        XSSFCellStyle noA = st.style(st.font(10, false, XlsxStyleService.MUTED, false), XlsxStyleService.WHITE, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle noB = st.style(st.font(10, false, XlsxStyleService.MUTED, false), XlsxStyleService.ZEBRA, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle inA = st.style(st.font(11, false, XlsxStyleService.INK, false), XlsxStyleService.WHITE, HorizontalAlignment.LEFT, true, false);
        XSSFCellStyle inB = st.style(st.font(11, false, XlsxStyleService.INK, false), XlsxStyleService.ZEBRA, HorizontalAlignment.LEFT, true, false);
        XSSFCellStyle example = st.style(st.font(11, false, XlsxStyleService.MUTED, true), XlsxStyleService.BRAND_SOFT, HorizontalAlignment.LEFT, true, false);
        XSSFCellStyle exampleNo = st.style(st.font(10, true, XlsxStyleService.BRAND, false), XlsxStyleService.BRAND_SOFT, HorizontalAlignment.CENTER, true, false);

        for (int i = 0; i < FORM_ROWS; i++) {
            Row row = sh.createRow(4 + i);
            row.setHeightInPoints(22);
            boolean sample = i == 0;
            boolean alt = i % 2 == 1;
            Cell no = row.createCell(COL_NO);
            no.setCellValue(sample ? "예시" : String.valueOf(i));
            no.setCellStyle(sample ? exampleNo : alt ? noB : noA);
            XSSFCellStyle body = sample ? example : alt ? inB : inA;
            for (int c = COL_NAME; c <= COL_NOTE; c++) row.createCell(c).setCellStyle(body);
            if (sample) {
                row.getCell(COL_NAME).setCellValue("홍길동");
                row.getCell(COL_EMAIL).setCellValue(SAMPLE_EMAIL);
                row.getCell(COL_NOTE).setCellValue("← 예시 행입니다. 지우거나 덮어써서 입력하세요");
            }
        }

        validations(sh);
        sh.createFreezePane(0, 4);
        footerSetup(sh, 3);
    }

    private static void validations(XSSFSheet sh) {
        DataValidationHelper dv = sh.getDataValidationHelper();
        int first = 4;
        int last = 4 + FORM_ROWS - 1;

        DataValidationConstraint nameLen = dv.createTextLengthConstraint(DataValidationConstraint.OperatorType.BETWEEN, "1", "50");
        DataValidation name = dv.createValidation(nameLen, new CellRangeAddressList(first, last, COL_NAME, COL_NAME));
        name.setShowErrorBox(true);
        name.createErrorBox("이름 확인", "이름은 1~50자로 입력하세요.");
        name.setShowPromptBox(true);
        name.createPromptBox("이름", "응시자 이름 (최대 50자)");
        sh.addValidationData(name);

        DataValidationConstraint mail = dv.createCustomConstraint("AND(ISNUMBER(FIND(\"@\",C5)),ISNUMBER(FIND(\".\",C5,FIND(\"@\",C5))),ISERROR(FIND(\" \",C5)))");
        DataValidation email = dv.createValidation(mail, new CellRangeAddressList(first, last, COL_EMAIL, COL_EMAIL));
        email.setShowErrorBox(true);
        email.createErrorBox("이메일 확인", "name@example.com 형식으로 입력하세요. 입장 링크가 이 주소로 발송됩니다.");
        email.setShowPromptBox(true);
        email.createPromptBox("이메일", "입장 링크를 받을 이메일 (사람마다 달라야 합니다)");
        sh.addValidationData(email);
    }

    private static void guideSheet(XSSFWorkbook wb, XlsxStyleService st) {
        XSSFSheet sh = wb.createSheet("작성 안내");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyleService.MUTED);
        XlsxRosterService.widths(sh, 8, 24, 70);

        XSSFCellStyle title = st.style(st.font(18, true, XlsxStyleService.WHITE, false), XlsxStyleService.BRAND, HorizontalAlignment.LEFT, false, false);
        title.setIndention((short) 1);
        st.band(sh, 0, 2, "작성 안내", title, 40);
        sh.createRow(1).setHeightInPoints(8);

        XSSFCellStyle head = st.style(st.font(11, true, XlsxStyleService.WHITE, false), XlsxStyleService.INK, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle num = st.style(st.font(11, true, XlsxStyleService.BRAND, false), XlsxStyleService.BRAND_SOFT, HorizontalAlignment.CENTER, true, false);
        XSSFCellStyle key = st.style(st.font(11, true, XlsxStyleService.INK, false), XlsxStyleService.ZEBRA, HorizontalAlignment.LEFT, true, true);
        XSSFCellStyle text = st.style(st.font(11, false, XlsxStyleService.INK, false), XlsxStyleService.WHITE, HorizontalAlignment.LEFT, true, true);

        Row h = sh.createRow(2);
        h.setHeightInPoints(26);
        String[] heads = {"순서", "항목", "내용"};
        for (int c = 0; c < heads.length; c++) {
            Cell cell = h.createCell(c);
            cell.setCellValue(heads[c]);
            cell.setCellStyle(head);
        }
        String[][] rows = {
                {"첫 행 유지", "'대상자 등록' 시트의 4행(번호·이름·이메일·비고)은 지우거나 바꾸지 마세요. 열 이름으로 이름과 이메일 열을 찾습니다."},
                {"이름 (필수)", "응시자 이름. 1~50자."},
                {"이메일 (필수)", "입장 링크를 받을 주소. 사람마다 달라야 하고, 같은 회차에 이미 등록된 주소는 건너뜁니다."},
                {"예시 행", "5행의 '예시'는 지우고 입력하세요. 그대로 올려도 등록되지 않습니다."},
                {"인원", "한 번에 최대 1000명까지 올릴 수 있습니다. 양식에는 " + FORM_ROWS + "행이 준비되어 있고, 더 필요하면 아래에 이어서 입력하세요."},
                {"제출", "저장한 뒤 회차 화면 > 대상자 > '엑셀·CSV 올리기'로 올립니다. 올리기 전에 오류가 있는 행과 이유를 먼저 보여 줍니다."},
                {"발송", "등록하면 바로 입장 링크 메일이 나갑니다. 시험 일시가 바뀌면 바뀐 일시로 다시 발송됩니다."},
        };
        for (int i = 0; i < rows.length; i++) {
            Row r = sh.createRow(3 + i);
            r.setHeightInPoints(rows[i][1].length() > 60 ? 44 : 26);
            Cell a = r.createCell(0);
            a.setCellValue(i + 1);
            a.setCellStyle(num);
            Cell b = r.createCell(1);
            b.setCellValue(rows[i][0]);
            b.setCellStyle(key);
            Cell c = r.createCell(2);
            c.setCellValue(rows[i][1]);
            c.setCellStyle(text);
        }
        footerSetup(sh, 2);
    }

    // ── 읽기 ──

    /** 대상자 xlsx 읽기: 첫 시트에서 '이름', '이메일' 열이 있는 행을 찾고(안내 띠가 위에 있어도 된다) 그 아래를 읽는다. 줄 번호는 엑셀 행 번호 */
    public static List<ParticipantCsvService.CsvRow> readParticipants(byte[] bytes, int maxRows) {
        return XlsxRosterService.readRows(bytes, HEADER_SEARCH_ROWS,
                cells -> {
                    int n = ParticipantCsvService.findColumn(cells, "이름", "name");
                    int e = ParticipantCsvService.findColumn(cells, "이메일", "email");
                    return n >= 0 && e >= 0 ? new int[] {n, e} : null;
                },
                "'이름', '이메일' 열을 찾지 못했습니다. 양식을 내려받아 써 주세요.", SAMPLE_EMAIL, 1, maxRows,
                "한 번에 " + maxRows + "명까지 올릴 수 있습니다.",
                (row, v) -> new ParticipantCsvService.CsvRow(row, v.get(0), v.get(1)));
    }

    /** 인쇄: 가로 한 쪽 너비에 맞추고, 머리글·바닥글에 플랫폼 이름을 넣는다 */
    private static void footerSetup(Sheet sh, int repeatHeaderRow) {
        XlsxRosterService.printSetup(sh, repeatHeaderRow, "pAIr 실전 시험 플랫폼", "pAIr");
    }
}
