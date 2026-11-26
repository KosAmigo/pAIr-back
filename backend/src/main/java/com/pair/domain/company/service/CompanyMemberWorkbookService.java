package com.pair.domain.company.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.pair.domain.company.dto.InquiryMemberInputDto;
import com.pair.domain.exam.service.XlsxStyleService;
import com.pair.global.security.CurrentUser;

/**
 * 기업 도입 문의의 회원 명단 엑셀 양식과 읽기. 회차 대상자 양식(ParticipantWorkbookService)과 같은 모양이다:
 * 제목 띠, 안내, 머리 행(회원명·회원이메일·역할), 예시 행, 입력 검사(역할은 목록에서 고름), 작성 안내 시트.
 * 읽을 때는 첫 시트에서 '회원명', '회원이메일', '역할' 열이 있는 행을 찾고 그 아래를 읽는다. 지우지 않은 예시 행은 건너뛴다.
 */
public final class CompanyMemberWorkbookService {

    /** 양식에 들어 있는 예시 행. 지우지 않고 올려도 명단에 넣지 않는다 */
    static final String SAMPLE_EMAIL = "manager@example.com";
    private static final int FORM_ROWS = 100;
    private static final int HEADER_SEARCH_ROWS = 15;
    private static final int FIRST_DATA_ROW = 4;
    private static final int COL_NO = 0, COL_NAME = 1, COL_EMAIL = 2, COL_ROLE = 3, COL_NOTE = 4, LAST_COL = 4;

    private CompanyMemberWorkbookService() {}

    /** xlsx 는 ZIP 이라 "PK" 로 시작한다 */
    public static boolean isXlsx(byte[] bytes) {
        return bytes != null && bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    // ── 양식 ──

    public static byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XlsxStyleService st = new XlsxStyleService(wb);
            formSheet(wb, st);
            guideSheet(wb, st);
            wb.getProperties().getCoreProperties().setTitle("pAIr 기업 회원 명단 양식");
            wb.getProperties().getCoreProperties().setCreator("pAIr");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("양식을 만들지 못했습니다.", e);
        }
    }

    private static void formSheet(XSSFWorkbook wb, XlsxStyleService st) {
        XSSFSheet sh = wb.createSheet("회원 명단");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyleService.BRAND);
        widths(sh, 8, 20, 38, 16, 30);

        XSSFCellStyle title = st.style(st.font(20, true, XlsxStyleService.WHITE, false), XlsxStyleService.BRAND, HorizontalAlignment.LEFT, false, false);
        title.setIndention((short) 1);
        st.band(sh, 0, LAST_COL, "pAIr   기업 회원 명단 양식", title, 44);

        XSSFCellStyle sub = st.style(st.font(10, false, XlsxStyleService.BRAND_DARK, false), XlsxStyleService.BRAND_SOFT, HorizontalAlignment.LEFT, false, true);
        sub.setIndention((short) 1);
        st.band(sh, 1, LAST_COL, "도입 문의와 함께 이 파일을 올리세요. 관리자가 승인하면 여기 적은 이메일로 기업 계정 초대 메일이 발송됩니다. "
                + "매니저(MANAGER)는 회차를 만들고 결과를 보고, 감독관(PROCTOR)은 시험 감독만 합니다.", sub, 34);
        sh.createRow(2).setHeightInPoints(8);

        XSSFCellStyle head = st.style(st.font(11, true, XlsxStyleService.WHITE, false), XlsxStyleService.INK, HorizontalAlignment.CENTER, true, false);
        Row header = sh.createRow(3);
        header.setHeightInPoints(28);
        String[] names = {"번호", "회원명 *", "회원이메일 *", "역할 *", "비고 (선택, 등록에 쓰지 않음)"};
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
            Row row = sh.createRow(FIRST_DATA_ROW + i);
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
                row.getCell(COL_ROLE).setCellValue("MANAGER");
                row.getCell(COL_NOTE).setCellValue("← 예시 행입니다. 지우거나 덮어써서 입력하세요");
            }
        }
        validations(sh);
        sh.createFreezePane(0, FIRST_DATA_ROW);
        printSetup(sh, 3);
    }

    private static void validations(XSSFSheet sh) {
        DataValidationHelper dv = sh.getDataValidationHelper();
        int first = FIRST_DATA_ROW, last = FIRST_DATA_ROW + FORM_ROWS - 1;

        DataValidation name = dv.createValidation(dv.createTextLengthConstraint(DataValidationConstraint.OperatorType.BETWEEN, "1", "50"),
                new CellRangeAddressList(first, last, COL_NAME, COL_NAME));
        name.setShowErrorBox(true);
        name.createErrorBox("회원명 확인", "회원명은 1~50자로 입력하세요.");
        sh.addValidationData(name);

        DataValidation email = dv.createValidation(
                dv.createCustomConstraint("AND(ISNUMBER(FIND(\"@\",C5)),ISNUMBER(FIND(\".\",C5,FIND(\"@\",C5))),ISERROR(FIND(\" \",C5)))"),
                new CellRangeAddressList(first, last, COL_EMAIL, COL_EMAIL));
        email.setShowErrorBox(true);
        email.createErrorBox("이메일 확인", "name@example.com 형식으로 입력하세요. 초대 메일이 이 주소로 발송됩니다.");
        email.setShowPromptBox(true);
        email.createPromptBox("회원이메일", "초대 메일을 받을 이메일 (사람마다 달라야 합니다)");
        sh.addValidationData(email);

        DataValidation role = dv.createValidation(dv.createExplicitListConstraint(new String[] {"MANAGER", "PROCTOR"}),
                new CellRangeAddressList(first, last, COL_ROLE, COL_ROLE));
        role.setShowErrorBox(true);
        role.createErrorBox("역할 확인", "MANAGER(매니저) 또는 PROCTOR(감독관) 중에서 고르세요.");
        role.setShowPromptBox(true);
        role.createPromptBox("역할", "MANAGER: 회차·결과 관리, PROCTOR: 시험 감독만");
        sh.addValidationData(role);
    }

    private static void guideSheet(XSSFWorkbook wb, XlsxStyleService st) {
        XSSFSheet sh = wb.createSheet("작성 안내");
        sh.setDisplayGridlines(false);
        sh.setTabColor(XlsxStyleService.MUTED);
        widths(sh, 8, 24, 70);

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
                {"첫 행 유지", "'회원 명단' 시트의 4행(번호·회원명·회원이메일·역할·비고)은 지우거나 바꾸지 마세요. 열 이름으로 각 열을 찾습니다."},
                {"회원명 (필수)", "기업 계정을 쓸 사람의 이름. 1~50자."},
                {"회원이메일 (필수)", "초대 메일을 받을 주소. 사람마다 달라야 하고, 이미 pAIr 에 가입된 주소는 쓸 수 없습니다."},
                {"역할 (필수)", "MANAGER(매니저): 회차를 만들고 대상자·결과를 관리합니다. PROCTOR(감독관): 시험 감독만 합니다. 매니저는 정확히 1명이어야 하고, 감독관은 없어도 됩니다."},
                {"예시 행", "5행의 '예시'는 지우고 입력하세요. 그대로 올려도 명단에 들어가지 않습니다."},
                {"제출", "저장한 뒤 로그인 화면 > 기업 도입 문의에서 이 파일(엑셀 또는 CSV)을 올립니다."},
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
        printSetup(sh, 2);
    }

    // ── 읽기 ──

    /** 엑셀 명단 읽기. 줄 번호는 엑셀 행 번호로 알려 준다 */
    public static List<InquiryMemberInputDto> read(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int headerRow = -1, nameCol = -1, emailCol = -1, roleCol = -1;
            for (int i = 0; i <= Math.min(sheet.getLastRowNum(), HEADER_SEARCH_ROWS) && headerRow < 0; i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                int n = -1, e = -1, ro = -1;
                for (Cell c : r) {
                    String v = fmt.formatCellValue(c).replace("*", "").trim();
                    if (v.equals("회원명")) n = c.getColumnIndex();
                    else if (v.equals("회원이메일")) e = c.getColumnIndex();
                    else if (v.equals("역할")) ro = c.getColumnIndex();
                }
                if (n >= 0 && e >= 0 && ro >= 0) {
                    headerRow = i;
                    nameCol = n;
                    emailCol = e;
                    roleCol = ro;
                }
            }
            if (headerRow < 0) throw new IllegalArgumentException("'회원명', '회원이메일', '역할' 열을 찾지 못했습니다. 양식을 내려받아 써 주세요.");

            List<InquiryMemberInputDto> rows = new ArrayList<>();
            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                String name = fmt.formatCellValue(r.getCell(nameCol)).trim();
                String email = fmt.formatCellValue(r.getCell(emailCol)).trim();
                String role = fmt.formatCellValue(r.getCell(roleCol)).trim();
                if (name.isEmpty() && email.isEmpty() && role.isEmpty()) continue;
                if (SAMPLE_EMAIL.equalsIgnoreCase(email)) continue; // 지우지 않은 예시 행
                CurrentUser.Role parsed;
                try {
                    parsed = CurrentUser.Role.valueOf(role.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    parsed = null;
                }
                if (parsed != CurrentUser.Role.MANAGER && parsed != CurrentUser.Role.PROCTOR) {
                    throw new IllegalArgumentException("엑셀 %d번째 행의 역할은 MANAGER 또는 PROCTOR여야 합니다.".formatted(i + 1));
                }
                rows.add(new InquiryMemberInputDto(name, email, parsed));
            }
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("엑셀 파일을 읽지 못했습니다. 양식을 내려받아 써 주세요.");
        }
    }

    // ── 공통 ──

    private static void widths(Sheet sh, int... chars) {
        for (int c = 0; c < chars.length; c++) sh.setColumnWidth(c, chars[c] * 256 + 512);
    }

    private static void printSetup(Sheet sh, int repeatHeaderRow) {
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(true);
        ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 0);
        sh.setFitToPage(true);
        sh.getFooter().setLeft("pAIr 기업 회원 명단");
        sh.getFooter().setRight("&P / &N");
        sh.setRepeatingRows(new CellRangeAddress(repeatHeaderRow, repeatHeaderRow, -1, -1));
    }
}
