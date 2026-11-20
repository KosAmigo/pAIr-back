package com.pair.exam.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailLayoutTest {

    @Test
    @DisplayName("사용자가 넣은 이름·제목의 HTML 은 이스케이프된다")
    void html_escapesUserInput() {
        String html = MailLayout.html("<script>alert(1)</script> 시험", "<b>홍길동</b>님", List.of(), List.of(), null, List.of());

        assertThat(html).doesNotContain("<script>").doesNotContain("<b>홍길동</b>");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;b&gt;홍길동&lt;/b&gt;");
    }

    @Test
    @DisplayName("단추 주소는 속성에 안전하게 들어가고, 단추가 없으면 링크를 그리지 않는다")
    void html_buttonUrlEscapedAndOptional() {
        String withButton = MailLayout.html("제목", "인사", List.of(), List.of(),
                new MailLayout.Button("입장", "https://x.test/entry?invite=a&b=\"c"), List.of());
        String without = MailLayout.html("제목", "인사", List.of(), List.of(), null, List.of());

        assertThat(withButton).contains("href=\"https://x.test/entry?invite=a&amp;b=&quot;c\"");
        assertThat(without).doesNotContain("<a ");
    }

    @Test
    @DisplayName("로고는 메일에 붙인 이미지(cid)를 가리키고, 시험 시간 같은 항목이 표로 나온다")
    void html_hasInlineLogoAndFacts() {
        String html = MailLayout.html("제목", "인사", List.of("본문"),
                List.of(new MailLayout.Fact("시험 시간", "2026-11-20 15:00")), null, List.of("안내"));

        assertThat(html).contains("src=\"cid:" + MailLayout.LOGO_CID + "\"")
                .contains("시험 시간").contains("2026-11-20 15:00").contains("본문").contains("안내");
    }
}
