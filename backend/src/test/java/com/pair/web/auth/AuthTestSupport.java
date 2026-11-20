package com.pair.web.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/** HTTP 밖에서 계정을 준비하는 통합 테스트용 이메일 인증 도우미. */
public final class AuthTestSupport {

    private static final Pattern CODE_PATTERN = Pattern.compile("인증 코드: ([0-9]{6})");

    private AuthTestSupport() {}

    public static AuthDtos.AuthResponse signup(AuthService auth, JavaMailSender mail, String name, String email) {
        auth.requestEmailVerification(email);
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        clearInvocations(mail);

        Matcher matcher = CODE_PATTERN.matcher(message.getValue().getText());
        if (!matcher.find()) throw new AssertionError("인증 코드가 메일 본문에 없습니다.");
        return auth.signup(new AuthDtos.SignupRequest(name, email, "password123", matcher.group(1)));
    }
}
