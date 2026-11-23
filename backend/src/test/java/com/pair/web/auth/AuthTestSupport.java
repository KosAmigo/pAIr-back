package com.pair.web.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HTTP 밖에서 계정을 준비하는 통합 테스트용 이메일 인증 도우미. */
public final class AuthTestSupport {

    private static final Pattern CODE_PATTERN = Pattern.compile("인증 코드: ([0-9]{6})");

    private AuthTestSupport() {}

    public static AuthDtos.AuthResponse signup(AuthService auth, JavaMailSender mail, String name, String email) {
        prepareMail(mail);
        auth.requestEmailVerification(email);
        return auth.signup(new AuthDtos.SignupRequest(name, email, "password123", sentCode(mail)));
    }

    /** 가짜 메일 발송기가 빈 메일(MimeMessage)을 만들어 주도록 한다. 인증 메일이 HTML 메일이라 요청 전에 부른다 */
    public static void prepareMail(JavaMailSender mail) {
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage((jakarta.mail.Session) null));
    }

    /** 방금 보낸 인증 메일의 글자 본문에서 6자리 코드를 꺼내고, 발송 기록을 비운다 */
    public static String sentCode(JavaMailSender mail) {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(message.capture());
        clearInvocations(mail);

        Matcher matcher = CODE_PATTERN.matcher(plainText(message.getValue()));
        if (!matcher.find()) throw new AssertionError("인증 코드가 메일 본문에 없습니다.");
        return matcher.group(1);
    }

    /** 메일 본문 중 글자(text/plain) 부분. 아직 저장하지 않은 메일이라 헤더가 아닌 데이터 핸들러의 종류를 본다 */
    public static String plainText(Part part) {
        try {
            String type = part.getDataHandler().getContentType();
            if (type.startsWith("text/plain")) return (String) part.getContent();
            if (type.startsWith("multipart/")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    String text = plainText(child);
                    if (!text.isEmpty()) return text;
                }
            }
            return "";
        } catch (Exception e) {
            throw new AssertionError("메일 본문을 읽지 못했습니다.", e);
        }
    }

    /** 메일 본문 중 HTML(text/html) 부분 */
    public static String htmlText(Part part) {
        try {
            String type = part.getDataHandler().getContentType();
            if (type.startsWith("text/html")) return (String) part.getContent();
            if (type.startsWith("multipart/")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    String html = htmlText(multipart.getBodyPart(i));
                    if (!html.isEmpty()) return html;
                }
            }
            return "";
        } catch (Exception e) {
            throw new AssertionError("메일 본문을 읽지 못했습니다.", e);
        }
    }
}
