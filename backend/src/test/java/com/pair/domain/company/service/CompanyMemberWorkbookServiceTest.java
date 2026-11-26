package com.pair.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import com.pair.domain.company.dto.InquiryMemberInputDto;
import com.pair.global.security.CurrentUser.Role;

/** 기업 회원 명단 엑셀 양식: 내려받은 양식에 그대로 써서 올리면 읽히고, CSV 도 계속 받는다 */
class CompanyMemberWorkbookServiceTest {

    /** 양식을 내려받아 5행(예시) 아래 6·7행에 입력한 파일 */
    private static byte[] filled(String... cells) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(CompanyMemberWorkbookService.template()))) {
            Sheet sh = wb.getSheet("회원 명단");
            for (int i = 0; i < cells.length / 3; i++) {
                Row r = sh.getRow(5 + i);
                r.getCell(1).setCellValue(cells[i * 3]);
                r.getCell(2).setCellValue(cells[i * 3 + 1]);
                r.getCell(3).setCellValue(cells[i * 3 + 2]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void templateHasFormAndGuideSheetsAndOnlySampleRow() throws Exception {
        byte[] template = CompanyMemberWorkbookService.template();
        assertThat(CompanyMemberWorkbookService.isXlsx(template)).isTrue();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheetName(0)).isEqualTo("회원 명단");
            assertThat(wb.getSheetName(1)).isEqualTo("작성 안내");
        }
        // 예시 행만 있는 양식을 그대로 올리면 명단이 비어 있다 (예시는 건너뛴다)
        assertThat(CompanyMemberWorkbookService.read(template)).isEmpty();
    }

    @Test
    void readsFilledRowsAndSkipsSample() throws Exception {
        List<InquiryMemberInputDto> rows = CompanyMemberWorkbookService.read(
                filled("김매니저", "boss@corp.com", "MANAGER", "이감독", "watch@corp.com", "proctor"));
        assertThat(rows).containsExactly(
                new InquiryMemberInputDto("김매니저", "boss@corp.com", Role.MANAGER),
                new InquiryMemberInputDto("이감독", "watch@corp.com", Role.PROCTOR));
    }

    @Test
    void rejectsRoleOtherThanManagerOrProctorWithExcelRowNumber() throws Exception {
        assertThatThrownBy(() -> CompanyMemberWorkbookService.read(filled("관리자", "admin@corp.com", "ADMIN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6번째 행");
    }

    @Test
    void rejectsWorkbookWithoutHeader() throws Exception {
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            wb.createSheet("빈 시트").createRow(0).createCell(0).setCellValue("아무거나");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            assertThatThrownBy(() -> CompanyMemberWorkbookService.read(out.toByteArray()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("양식을 내려받아");
        }
    }

    @Test
    void rosterAcceptsBothExcelAndCsv() throws Exception {
        assertThat(CompanyInquiryService.parseRoster(filled("김매니저", "boss@corp.com", "MANAGER"))).hasSize(1);
        byte[] csv = "﻿회원명,회원이메일,역할\r\n김매니저,boss@corp.com,MANAGER\r\n".getBytes(StandardCharsets.UTF_8);
        assertThat(CompanyInquiryService.parseRoster(csv))
                .containsExactly(new InquiryMemberInputDto("김매니저", "boss@corp.com", Role.MANAGER));
    }
}
