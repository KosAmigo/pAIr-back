package com.pair.domain.taskgen.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/**
 * AI 가 쓴 쪽별 글로 PDF 를 만든다 (PDFBox). 한글 글꼴은 Pretendard(resources/fonts, OFL).
 * 글 한 쪽이 PDF 한 쪽이 되게 만든다. 한 쪽 글이 한 장에 다 안 들어가면 다음 장으로 넘기며, 그때는 쪽 수가 늘어난다
 * (결과의 pageTexts 가 실제 PDF 쪽별 글이라 쪽 수를 비교해 알 수 있다).
 * 글꼴에 없는 글자(이모지 등)가 있으면 IllegalArgumentException.
 */
@Component
public class PdfMaker {

    /** bytes: PDF 파일, pageTexts: PDF 쪽마다 들어간 글(document_pages 에 그대로 넣는다) */
    public record Result(byte[] bytes, List<String> pageTexts) {}

    private static final PDRectangle SIZE = PDRectangle.A4;
    private static final float MARGIN = 56f;
    private static final float BODY = 11f;
    private static final float TITLE = 16f;
    private static final float LEADING = 17f;

    /** title 은 첫 쪽 맨 위에 굵게 쓴다 */
    public Result make(String title, List<String> pages) {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = font(doc, "Regular");
            PDFont bold = font(doc, "Bold");
            float width = SIZE.getWidth() - MARGIN * 2;
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                List<String> lines = wrap(clean(pages.get(i)), regular, BODY, width);
                int p = 0;
                int idx = 0;
                boolean first = i == 0;
                do {
                    PDPage page = new PDPage(SIZE);
                    doc.addPage(page);
                    float y = SIZE.getHeight() - MARGIN;
                    StringBuilder text = new StringBuilder();
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        if (first && p == 0) {
                            cs.beginText();
                            cs.setFont(bold, TITLE);
                            cs.newLineAtOffset(MARGIN, y - TITLE);
                            cs.showText(clean(title));
                            cs.endText();
                            y -= TITLE + LEADING;
                        }
                        cs.beginText();
                        cs.setFont(regular, BODY);
                        cs.newLineAtOffset(MARGIN, y - BODY);
                        float cur = y - BODY;
                        while (idx < lines.size() && cur >= MARGIN) {
                            String line = lines.get(idx++);
                            if (!line.isEmpty()) cs.showText(line);
                            cs.newLineAtOffset(0, -LEADING);
                            cur -= LEADING;
                            if (text.length() > 0) text.append('\n');
                            text.append(line);
                        }
                        cs.endText();
                    }
                    texts.add(text.toString());
                    p++;
                } while (idx < lines.size());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new Result(out.toByteArray(), texts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDFont font(PDDocument doc, String weight) throws IOException {
        try (InputStream in = PdfMaker.class.getResourceAsStream("/fonts/Pretendard-" + weight + ".ttf")) {
            if (in == null) throw new IllegalStateException("글꼴 파일을 찾지 못했습니다: Pretendard-" + weight + ".ttf");
            return PDType0Font.load(doc, in);
        }
    }

    /** 줄바꿈 기호를 맞추고 탭을 공백으로, 나머지 제어 문자는 뺀다 */
    private static String clean(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").replaceAll("[\\p{Cntrl}&&[^\n]]", "");
    }

    /** 너비를 넘으면 공백에서, 공백이 없으면 글자 단위로 줄을 바꾼다 */
    private static List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int lastSpace = -1;
            for (int i = 0; i < para.length(); i++) {
                char c = para.charAt(i);
                line.append(c);
                if (c == ' ') lastSpace = line.length() - 1;
                if (font.getStringWidth(line.toString()) / 1000f * size > width && line.length() > 1) {
                    int cut = lastSpace > 0 ? lastSpace : line.length() - 1;
                    out.add(line.substring(0, cut).stripTrailing());
                    String rest = line.substring(lastSpace > 0 ? cut + 1 : cut);
                    line = new StringBuilder(rest);
                    lastSpace = -1;
                }
            }
            out.add(line.toString().stripTrailing());
        }
        return out;
    }
}
