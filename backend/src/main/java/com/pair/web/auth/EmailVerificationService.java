package com.pair.web.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 이메일 인증 코드 발급·발송·검증을 처리한다. */
@Service
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PasswordEncoder CODE_ENCODER = new BCryptPasswordEncoder(10);

    private final EmailVerificationRepository verifications;
    private final JavaMailSender mail;
    private final String mailFrom;
    private final Duration codeLifetime;
    private final Duration resendInterval;
    private final int maxAttempts;

    public EmailVerificationService(EmailVerificationRepository verifications, JavaMailSender mail,
            @Value("${pair.auth.email-from:no-reply@pair.test}") String mailFrom,
            @Value("${pair.auth.email-verification.code-lifetime:10m}") Duration codeLifetime,
            @Value("${pair.auth.email-verification.resend-interval:60s}") Duration resendInterval,
            @Value("${pair.auth.email-verification.max-attempts:5}") int maxAttempts) {
        this.verifications = verifications;
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.codeLifetime = codeLifetime;
        this.resendInterval = resendInterval;
        this.maxAttempts = maxAttempts;
        if (codeLifetime.isZero() || codeLifetime.isNegative()
                || resendInterval.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("이메일 인증 설정값이 올바르지 않습니다.");
        }
    }

    @Transactional
    public void sendCode(String email) {
        Instant now = Instant.now();
        EmailVerification verification = verifications.findByEmailForUpdate(email).orElse(null);
        if (verification != null && now.isBefore(verification.getCreatedAt().plus(resendInterval))) {
            throw new IllegalStateException("인증 코드는 %d분 후 다시 요청할 수 있습니다."
                    .formatted(Math.max(1, resendInterval.toMinutes())));
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        if (verification == null) {
            verification = new EmailVerification(email, CODE_ENCODER.encode(code), now, now.plus(codeLifetime));
        } else {
            verification.replaceCode(CODE_ENCODER.encode(code), now, now.plus(codeLifetime));
        }
        verifications.save(verification);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("[pAIr] 회원가입 이메일 인증 코드");
        message.setText("""
                pAIr 회원가입 인증 코드입니다.

                인증 코드: %s
                코드는 발송 시각부터 %d분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code, Math.max(1, codeLifetime.toMinutes())));
        try {
            mail.send(message);
        } catch (MailException e) {
            throw new MailDeliveryException(e);
        }
    }

    /** 별도 트랜잭션으로 성공 코드를 소비하고 실패 횟수를 보존한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConsumeResult consume(String email, String code) {
        EmailVerification verification = verifications.findByEmailForUpdate(email).orElse(null);
        if (verification == null || verification.getConsumedAt() != null) return ConsumeResult.NOT_FOUND;

        Instant now = Instant.now();
        if (!verification.getExpiresAt().isAfter(now)) return ConsumeResult.EXPIRED;
        if (verification.getFailedAttempts() >= maxAttempts) return ConsumeResult.LOCKED;
        if (!CODE_ENCODER.matches(code, verification.getCodeHash())) {
            verification.recordFailure();
            return verification.getFailedAttempts() >= maxAttempts ? ConsumeResult.LOCKED : ConsumeResult.INVALID;
        }

        verification.consume(now);
        return ConsumeResult.CONSUMED;
    }

    enum ConsumeResult {
        CONSUMED(""),
        NOT_FOUND("이메일 인증 코드를 먼저 요청해 주세요."),
        EXPIRED("인증 코드가 만료되었습니다. 새 코드를 요청해 주세요."),
        INVALID("인증 코드가 올바르지 않습니다."),
        LOCKED("인증 시도 횟수를 초과했습니다. 새 코드를 요청해 주세요.");

        private final String message;

        ConsumeResult(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }
}
