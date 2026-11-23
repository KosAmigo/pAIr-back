package com.pair.infra.mail;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

/**
 * 회차 메일의 HTML 본문. 메일 클라이언트(Gmail, 네이버 등)는 &lt;style&gt;·외부 CSS 를 잘 지원하지 않아
 * 표(table) 레이아웃과 인라인 스타일로만 만든다. 로고는 메일에 붙인 이미지(cid:logo)를 쓴다.
 * 사용자가 넣은 값(이름, 회차 제목)은 모두 이스케이프한다.
 */
public final class MailLayout {
    public static final String LOGO_CID = "logo";
    /** 머리띠에 쓰는 로고 이미지. 메일마다 {@link #LOGO_CID} 로 붙인다 */
    public static final ClassPathResource LOGO = new ClassPathResource("mail/logo.png");

    private static final String DEFAULT_FOOTER = "이 메일은 pAIr 시험 안내를 위해 자동으로 발송되었습니다. 회신하지 않아도 됩니다.";
    private static final String BRAND = "#6C47FF";
    private static final String TEXT = "#1F2430";
    private static final String MUTED = "#6B7280";

    private MailLayout() {
    }

    /** 표 한 줄: 라벨과 값 (예: 시험 시간) */
    public record Fact(String label, String value) {
    }

    /** 단추. url 이 null 이면 단추를 그리지 않는다 */
    public record Button(String label, String url) {
    }

    /** 시험 안내 메일 본문 (맨 아래 문구는 시험 안내용) */
    public static String html(String heading, String greeting, List<String> paragraphs, List<Fact> facts,
            Button button, List<String> notes) {
        return html(heading, greeting, paragraphs, facts, button, notes, DEFAULT_FOOTER);
    }

    /** footer: 맨 아래 회색 줄. 메일 종류에 맞는 문구를 넣는다 */
    public static String html(String heading, String greeting, List<String> paragraphs, List<Fact> facts,
            Button button, List<String> notes, String footer) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>")
                .append("<body style=\"margin:0;padding:0;background:#F3F4F8;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#F3F4F8;padding:24px 12px;\"><tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;max-width:560px;background:#FFFFFF;border-radius:16px;overflow:hidden;font-family:'Malgun Gothic','Apple SD Gothic Neo',Arial,sans-serif;color:")
                .append(TEXT).append(";\">");

        // 머리띠: 로고 + 이름. 그라데이션을 못 그리는 클라이언트를 위해 단색 배경을 먼저 둔다
        sb.append("<tr><td style=\"background:").append(BRAND)
                .append(";background-image:linear-gradient(120deg,#8B5CF6,#6C47FF 45%,#2F5BEA);padding:24px 32px;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                .append("<td style=\"padding-right:12px;\"><img src=\"cid:").append(LOGO_CID)
                .append("\" width=\"40\" height=\"40\" alt=\"\" style=\"display:block;border:0;border-radius:10px;\"></td>")
                .append("<td style=\"font-size:22px;font-weight:700;color:#FFFFFF;letter-spacing:0.5px;\">pAIr</td>")
                .append("</tr></table></td></tr>");

        sb.append("<tr><td style=\"padding:32px 32px 8px 32px;\">")
                .append("<div style=\"font-size:22px;font-weight:700;line-height:1.4;color:").append(TEXT).append(";\">")
                .append(esc(heading)).append("</div>")
                .append("<div style=\"margin-top:12px;font-size:15px;line-height:1.7;color:").append(TEXT).append(";\">")
                .append(esc(greeting)).append("</div>");
        for (String p : paragraphs) {
            sb.append("<div style=\"margin-top:8px;font-size:15px;line-height:1.7;color:").append(TEXT).append(";\">")
                    .append(esc(p)).append("</div>");
        }
        sb.append("</td></tr>");

        if (!facts.isEmpty()) {
            sb.append("<tr><td style=\"padding:16px 32px 0 32px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#F7F5FF;border-radius:12px;\">");
            for (Fact f : facts) {
                sb.append("<tr><td style=\"padding:12px 16px 4px 16px;font-size:12px;color:").append(MUTED).append(";\">")
                        .append(esc(f.label())).append("</td></tr>")
                        .append("<tr><td style=\"padding:0 16px 12px 16px;font-size:16px;font-weight:700;color:").append(TEXT).append(";\">")
                        .append(esc(f.value())).append("</td></tr>");
            }
            sb.append("</table></td></tr>");
        }

        if (button != null && button.url() != null) {
            String url = HtmlUtils.htmlEscape(button.url());
            sb.append("<tr><td align=\"center\" style=\"padding:28px 32px 8px 32px;\">")
                    .append("<a href=\"").append(url).append("\" style=\"display:inline-block;background:").append(BRAND)
                    .append(";background-image:linear-gradient(120deg,#8B5CF6,#6C47FF 45%,#2F5BEA);color:#FFFFFF;text-decoration:none;font-size:16px;font-weight:700;padding:14px 36px;border-radius:999px;\">")
                    .append(esc(button.label())).append("</a>")
                    .append("<div style=\"margin-top:12px;font-size:12px;line-height:1.6;color:").append(MUTED)
                    .append(";word-break:break-all;\">단추가 열리지 않으면 아래 주소를 브라우저에 붙여 넣으세요.<br>")
                    .append("<a href=\"").append(url).append("\" style=\"color:").append(MUTED).append(";\">").append(url)
                    .append("</a></div></td></tr>");
        }

        sb.append("<tr><td style=\"padding:20px 32px 28px 32px;\">");
        for (String n : notes) {
            sb.append("<div style=\"margin-top:6px;font-size:13px;line-height:1.6;color:").append(MUTED).append(";\">")
                    .append(esc(n)).append("</div>");
        }
        sb.append("</td></tr>")
                .append("<tr><td style=\"padding:16px 32px;background:#FAFAFC;font-size:12px;color:").append(MUTED)
                .append(";text-align:center;\">").append(esc(footer)).append("</td></tr>")
                .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }
}
