package com.pair.domain.company.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.company.dto.CredentialsIssuedDto;

/**
 * 기업 계정의 임시 비밀번호를 만들고 메일로 보낸다. 승인·재발급 트랜잭션이 커밋된 뒤 별도 스레드에서 한 명씩 처리한다.
 * BCrypt 해시와 메일 발송은 트랜잭션 밖에서 하고, DB 저장만 짧은 트랜잭션으로 한다.
 * 서버가 중간에 꺼지면 남은 계정은 PENDING 이고, 온보딩 재발급으로 다시 보낼 수 있다.
 */
@Service
public class CorporateCredentialService {

    private static final Logger log = LoggerFactory.getLogger(CorporateCredentialService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthAccountRepository accounts;
    private final CompanyAccountMailService mailer;
    private final TransactionTemplate tx;

    public CorporateCredentialService(AuthAccountRepository accounts, CompanyAccountMailService mailer,
            TransactionTemplate tx) {
        this.accounts = accounts;
        this.mailer = mailer;
        this.tx = tx;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIssued(CredentialsIssuedDto event) {
        for (Long id : event.accountIds()) {
            try {
                issue(id, event.placeholderHash());
            } catch (RuntimeException e) {
                // 한 명이 실패해도 나머지는 계속 보낸다. 이 계정은 재발급으로 다시 보낼 수 있다
                log.warn("기업 계정 임시 비밀번호 발급 실패: userId={} ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    public void issue(Long accountId, String placeholderHash) {
        String password = temporaryPassword();
        String hash = CorporateAccountService.hashSignupPassword(password);
        AuthAccount account = updateIfCurrent(accountId, placeholderHash, a -> a.setTemporaryPassword(hash));
        if (account == null) return;
        String result;
        try {
            mailer.sendCredentials(account, password);
            result = "SENT";
        } catch (RuntimeException e) {
            log.warn("기업 계정 메일 발송 실패: userId={} ({})", accountId, e.getClass().getSimpleName());
            result = "FAILED";
        }
        String status = result;
        updateIfCurrent(accountId, hash, a -> a.markOnboardingMail(status));
    }

    /** 그 사이 재발급·탈퇴 등으로 비밀번호가 바뀐 계정은 건드리지 않는다. */
    private AuthAccount updateIfCurrent(Long accountId, String expectedHash, Consumer<AuthAccount> change) {
        return tx.execute(status -> {
            AuthAccount account = accounts.lockById(accountId).orElse(null);
            if (account == null || !account.isActive() || !account.getPasswordHash().equals(expectedHash)) return null;
            change.accept(account);
            return account;
        });
    }

    private static String temporaryPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
