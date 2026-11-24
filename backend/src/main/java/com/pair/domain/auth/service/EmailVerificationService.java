package com.pair.domain.auth.service;

import com.pair.domain.auth.entity.EmailVerification;
import com.pair.domain.auth.repository.EmailVerificationRepository;
import com.pair.infra.mail.MailDeliveryException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pair.infra.mail.MailLayout;

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
        sendCode(email, false);
    }

    @Transactional
    public void sendOnboardingCode(String email) {
        sendCode(email, true);
    }

    private void sendCode(String email, boolean noExpiry) {
        Instant now = Instant.now();
        EmailVerification verification = verifications.findByEmailForUpdate(email).orElse(null);
        if (verification != null && now.isBefore(verification.getCreatedAt().plus(resendInterval))) {
            throw new IllegalStateException("인증 코드는 %d분 후 다시 요청할 수 있습니다."
                    .formatted(Math.max(1, resendInterval.toMinutes())));
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        if (verification == null) {
            verification = new EmailVerification(email, CODE_ENCODER.encode(code), now, noExpiry ? null : now.plus(codeLifetime));
        } else {
            verification.replaceCode(CODE_ENCODER.encode(code), now, noExpiry ? null : now.plus(codeLifetime));
        }
        verifications.save(verification);

        try {
            mail.send(codeMessage(email, code, noExpiry));
        } catch (MailException e) {
            throw new MailDeliveryException(e);
        }
    }

    /** 시험 초대 메일과 같은 서식(머리띠·표·안내 문구)으로 만든 인증 코드 메일. 글자 본문도 함께 담는다 */
    private MimeMessage codeMessage(String email, String code, boolean noExpiry) {
        long minutes = Math.max(1, codeLifetime.toMinutes());
        String expiry = noExpiry ? "새 코드를 요청하거나 인증에 성공할 때까지 유효합니다."
                : "발송 시각부터 %d분 동안 유효합니다.".formatted(minutes);
        String text = """
                pAIr 회원가입 인증 코드입니다.

                인증 코드: %s
                %s
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code, expiry);
        String html = MailLayout.html("회원가입 이메일 인증 코드", "안녕하세요.",
                List.of("pAIr 회원가입 인증 코드입니다. 아래 코드를 가입 화면에 입력해 주세요."),
                List.of(new MailLayout.Fact("인증 코드", code),
                        new MailLayout.Fact("유효 시간", noExpiry ? "새 코드 발급 또는 인증 성공 시까지" : "발송 시각부터 " + minutes + "분")),
                null,
                List.of("본인이 요청하지 않았다면 이 메일을 무시해 주세요.",
                        "인증 코드는 본인만 사용하세요. 다른 사람과 공유하지 마세요."),
                "이 메일은 pAIr 회원가입 인증을 위해 자동으로 발송되었습니다. 회신하지 않아도 됩니다.");
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(email);
            helper.setSubject("[pAIr] 회원가입 이메일 인증 코드");
            helper.setText(text, html);
            helper.addInline(MailLayout.LOGO_CID, MailLayout.LOGO, "image/png");
            return mime;
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }

    /**
     * 호출한 쪽 트랜잭션에서 코드를 소비한다. 커넥션을 하나 더 쓰지 않고, 가입이 실패하면 코드 소비도 함께 취소된다.
     * 실패 횟수를 남기려면 호출하는 쪽이 noRollbackFor = EmailVerificationException.class 를 지정하고 그 예외를 던져야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumeResult consume(String email, String code) {
        EmailVerification verification = verifications.findByEmailForUpdate(email).orElse(null);
        if (verification == null || verification.getConsumedAt() != null) return ConsumeResult.NOT_FOUND;

        Instant now = Instant.now();
        if (verification.getExpiresAt() != null && !verification.getExpiresAt().isAfter(now)) return ConsumeResult.EXPIRED;
        if (verification.getFailedAttempts() >= maxAttempts) return ConsumeResult.LOCKED;
        if (!CODE_ENCODER.matches(code, verification.getCodeHash())) {
            verification.recordFailure();
            return verification.getFailedAttempts() >= maxAttempts ? ConsumeResult.LOCKED : ConsumeResult.INVALID;
        }

        verification.consume(now);
        return ConsumeResult.CONSUMED;
    }

    @Transactional
    public void removeForEmail(String email) {
        verifications.deleteById(email);
    }

    public enum ConsumeResult {
        CONSUMED(""),
        NOT_FOUND("이메일 인증 코드를 먼저 요청해 주세요."),
        EXPIRED("인증 코드가 만료되었습니다. 새 코드를 요청해 주세요."),
        INVALID("인증 코드가 올바르지 않습니다."),
        LOCKED("인증 시도 횟수를 초과했습니다. 새 코드를 요청해 주세요.");

        private final String message;

        ConsumeResult(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
