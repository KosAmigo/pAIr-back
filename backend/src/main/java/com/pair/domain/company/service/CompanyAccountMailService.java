package com.pair.domain.company.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.pair.infra.mail.MailLayout;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.infra.mail.MailDeliveryException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Component
public class CompanyAccountMailService {

    private final JavaMailSender mail;
    private final String mailFrom;
    private final String loginUrl;

    public CompanyAccountMailService(JavaMailSender mail,
            @Value("${pair.auth.email-from:no-reply@pair.test}") String mailFrom,
            @Value("${pair.company.login-url:https://localhost:5173/login}") String loginUrl) {
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.loginUrl = loginUrl;
    }

    public void sendCredentials(AuthAccount account, String temporaryPassword) {
        String role = account.getRole().name();
        String text = """
                pAIr 기업 계정이 생성되었습니다.

                이름: %s
                이메일: %s
                역할: %s
                임시 비밀번호: %s

                로그인: %s
                로그인 후 이메일 인증과 비밀번호 변경을 완료해 주세요. 완료 전에는 다른 기능을 사용할 수 없습니다.
                """.formatted(account.getName(), account.getEmail(), role, temporaryPassword, loginUrl);
        String html = MailLayout.html("pAIr 기업 계정 안내", account.getName() + "님, 안녕하세요.",
                List.of("기업 계정이 생성되었습니다. 아래 임시 비밀번호로 로그인해 주세요.",
                        "로그인 후 이메일 인증과 비밀번호 변경을 완료해야 서비스를 이용할 수 있습니다."),
                List.of(new MailLayout.Fact("이름", account.getName()),
                        new MailLayout.Fact("이메일", account.getEmail()),
                        new MailLayout.Fact("역할", role),
                        new MailLayout.Fact("임시 비밀번호", temporaryPassword)),
                new MailLayout.Button("로그인", loginUrl),
                List.of("비밀번호를 다른 사람과 공유하지 마세요."),
                "이 메일은 pAIr 기업 계정 생성을 위해 자동으로 발송되었습니다.");
        send(account.getEmail(), "[pAIr] 기업 계정 및 임시 비밀번호", text, html);
    }

    public void sendProctorInvite(String email, String name, String inviteUrl) {
        String text = """
                %s님, pAIr 기업 감독관 계정 초대입니다.

                아래 링크에서 이메일 인증과 회원가입을 완료해 주세요.
                %s
                """.formatted(name, inviteUrl);
        String html = MailLayout.html("pAIr 감독관 초대", name + "님, 안녕하세요.",
                List.of("같은 회사의 pAIr 기업 감독관으로 초대되었습니다.",
                        "초대 이메일 주소로 인증한 뒤 회원가입을 완료해 주세요."),
                List.of(new MailLayout.Fact("초대 이메일", email)),
                new MailLayout.Button("초대 수락 및 가입", inviteUrl),
                List.of("초대 링크를 다른 사람과 공유하지 마세요."),
                "이 메일은 pAIr 기업 계정 초대를 위해 자동으로 발송되었습니다.");
        send(email, "[pAIr] 기업 감독관 초대", text, html);
    }

    private void send(String to, String subject, String text, String html) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            helper.addInline(MailLayout.LOGO_CID, MailLayout.LOGO, "image/png");
            mail.send(mime);
        } catch (MailException e) {
            throw new MailDeliveryException(e);
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }
}
