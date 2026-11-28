package com.pair.infra.mail;

import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * 메일 한 통을 만들어 보내는 공용 창구. 글자 본문과 HTML 본문을 함께 담고 로고를 인라인으로 붙인다.
 * MIME 을 만들다 실패하면 {@link MailPreparationException}, 보내다 실패하면 Spring 의 MailException 을 그대로 던진다.
 * 실패 처리(기록, 재시도, 예외 바꾸기)는 부르는 쪽이 정한다.
 */
@Component
public class MailClient {

    private final JavaMailSender mail;

    public MailClient(JavaMailSender mail) {
        this.mail = mail;
    }

    public void send(String from, String to, String subject, String text, String html) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            helper.addInline(MailLayout.LOGO_CID, MailLayout.LOGO, "image/png");
            mail.send(mime);
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }
}
