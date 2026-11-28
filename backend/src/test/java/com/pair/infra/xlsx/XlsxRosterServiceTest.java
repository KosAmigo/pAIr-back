package com.pair.infra.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import com.pair.domain.exam.service.ParticipantCsvService;
import com.pair.domain.exam.service.ParticipantWorkbookService;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxRosterServiceTest {

    private static byte[] book(int headerRow, String[][] rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet();
            sh.createRow(0).createCell(0).setCellValue("안내 띠");
            sh.createRow(headerRow).createCell(1).setCellValue("이름 *");
            sh.getRow(headerRow).createCell(2).setCellValue("이메일 *");
            for (int i = 0; i < rows.length; i++) {
                if (rows[i] == null) continue; // 건너뛴 행
                var r = sh.createRow(headerRow + 1 + i);
                r.createCell(1).setCellValue(rows[i][0]);
                r.createCell(2).setCellValue(rows[i][1]);
            }
            return XlsxRosterService.toBytes(wb);
        }
    }

    private static List<ParticipantCsvService.CsvRow> read(byte[] b, int max) {
        return ParticipantWorkbookService.readParticipants(b, max);
    }

    @Test
    void headerRowIsFoundBelowIntroBandAndRowNumbersAreExcelRows() throws IOException {
        var rows = read(book(3, new String[][] {{"가", "a@x.com"}, {"나", "b@x.com"}}), 10);
        assertThat(rows).extracting(ParticipantCsvService.CsvRow::line).containsExactly(5, 6);
        assertThat(rows).extracting(ParticipantCsvService.CsvRow::email).containsExactly("a@x.com", "b@x.com");
    }

    @Test
    void emptyAndSampleRowsAreSkipped() throws IOException {
        var rows = read(book(1, new String[][] {{"홍길동", "HONG@example.com"}, {"", ""}, null, {"다", "c@x.com"}}), 10);
        assertThat(rows).extracting(ParticipantCsvService.CsvRow::name).containsExactly("다");
    }

    @Test
    void missingHeaderIsReported() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet().createRow(0).createCell(0).setCellValue("아무 글");
            byte[] b = XlsxRosterService.toBytes(wb);
            assertThatThrownBy(() -> read(b, 10)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("열을 찾지 못했습니다");
        }
    }

    @Test
    void tooManyRowsIsRejected() throws IOException {
        byte[] b = book(0, new String[][] {{"가", "a@x.com"}, {"나", "b@x.com"}, {"다", "c@x.com"}});
        assertThat(read(b, 3)).hasSize(3);
        assertThatThrownBy(() -> read(b, 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("한 번에 2명까지 올릴 수 있습니다.");
    }

    @Test
    void unreadableFileBecomesFriendlyError() {
        byte[] junk = "PK-not-really-xlsx".getBytes();
        assertThatThrownBy(() -> read(junk, 10)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("엑셀 파일을 읽지 못했습니다. 양식을 내려받아 써 주세요.");
    }

    @Test
    void isXlsxChecksZipMagic() {
        assertThat(XlsxRosterService.isXlsx(new byte[] {'P', 'K', 3})).isTrue();
        assertThat(XlsxRosterService.isXlsx("a,b".getBytes())).isFalse();
        assertThat(XlsxRosterService.isXlsx(null)).isFalse();
    }
}
