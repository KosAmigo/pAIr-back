package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 만든 PDF 를 PDFBox 로 다시 읽어 쪽 수와 한글 글이 맞는지 */
@Tag("slow")
class PdfServiceTest {

    private final PdfService maker = new PdfService();

    private static String text(byte[] pdf, int page) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(doc);
        }
    }

    @Test
    @DisplayName("글 한 쪽이 PDF 한 쪽이 되고, 한글 제목과 본문이 그대로 읽힌다")
    void pagesAndKorean() throws Exception {
        var r = maker.make("설문 요약", List.of("첫째 쪽입니다. 월 이탈률은 6.8%로 올랐습니다.\n둘째 줄", "둘째 쪽: 응답자의 38%가 찬성했습니다."));
        try (PDDocument doc = Loader.loadPDF(r.bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(r.pageTexts()).hasSize(2);
        assertThat(text(r.bytes(), 1)).contains("설문 요약").contains("첫째 쪽입니다").contains("6.8%").contains("둘째 줄");
        assertThat(text(r.bytes(), 2)).contains("둘째 쪽").contains("38%가 찬성했습니다");
    }

    @Test
    @DisplayName("긴 글은 줄바꿈하고 다음 장으로 넘긴다 (쪽 수가 늘고, 글은 빠지지 않는다)")
    void longTextWraps() throws Exception {
        String line = "이 문장은 한 줄보다 길어서 자동으로 줄이 바뀌어야 하며 끝까지 빠짐없이 들어가야 합니다 ";
        String body = (line.repeat(6) + "\n").repeat(40) + "마지막 문장입니다";
        var r = maker.make("긴 문서", List.of(body));
        try (PDDocument doc = Loader.loadPDF(r.bytes())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1).isEqualTo(r.pageTexts().size());
        }
        String all = text(r.bytes(), 1) + text(r.bytes(), r.pageTexts().size());
        assertThat(all).contains("긴 문서").contains("마지막 문장입니다");
        assertThat(String.join("", r.pageTexts()).replaceAll("\\s", "")).contains("마지막문장입니다");
    }

    @Test
    @DisplayName("글꼴에 없는 글자는 IllegalArgumentException")
    void unsupportedGlyph() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> maker.make("t", List.of("😀")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
