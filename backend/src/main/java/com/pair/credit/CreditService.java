package com.pair.credit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pair.web.auth.AuthAccountRepository;

@Service
public class CreditService {
    private final AuthAccountRepository accounts;
    private final CreditLedgerRepository ledger;
    private final int signupCredits;
    private final int attemptCost;
    private final int chatCost;
    private final int rollbackCost;

    public CreditService(AuthAccountRepository accounts, CreditLedgerRepository ledger,
            @Value("${pair.credit.signup:200}") int signupCredits,
            @Value("${pair.credit.attempt:10}") int attemptCost,
            @Value("${pair.credit.chat:10}") int chatCost,
            @Value("${pair.credit.rollback:10}") int rollbackCost) {
        if (signupCredits <= 0 || attemptCost <= 0 || chatCost <= 0 || rollbackCost <= 0) {
            throw new IllegalArgumentException("크레딧 지급량과 차감량은 양수여야 합니다.");
        }
        this.accounts = accounts;
        this.ledger = ledger;
        this.signupCredits = signupCredits;
        this.attemptCost = attemptCost;
        this.chatCost = chatCost;
        this.rollbackCost = rollbackCost;
    }

    @Transactional(readOnly = true)
    public int balance(Long userId) {
        return ledger.findFirstByUserIdOrderByIdDesc(userId).map(CreditLedger::getBalanceAfter).orElse(0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantSignup(Long userId) {
        lock(userId);
        if (ledger.findFirstByUserIdOrderByIdDesc(userId).isPresent()) {
            throw new IllegalStateException("이미 크레딧 내역이 있는 회원입니다.");
        }
        ledger.save(new CreditLedger(userId, signupCredits, signupCredits, "SIGNUP", null, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkAttempt(Long userId) {
        check(userId, attemptCost);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeAttempt(Long userId, Long attemptId) {
        charge(userId, attemptCost, "ATTEMPT_START", attemptId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkChat(Long userId) {
        check(userId, chatCost);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeChat(Long userId, Long turnId) {
        charge(userId, chatCost, "CHAT_TURN", null, turnId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkRollback(Long userId) {
        check(userId, rollbackCost);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeRollback(Long userId, Long attemptId) {
        charge(userId, rollbackCost, "ROLLBACK", attemptId, null);
    }

    private void charge(Long userId, int cost, String reason, Long attemptId, Long turnId) {
        int current = check(userId, cost);
        ledger.save(new CreditLedger(userId, -cost, current - cost, reason, attemptId, turnId));
    }

    private int check(Long userId, int cost) {
        lock(userId);
        int current = balance(userId);
        if (current < cost) {
            throw new InsufficientCreditException("크레딧이 부족합니다. 필요: " + cost + ", 잔액: " + current);
        }
        return current;
    }

    private void lock(Long userId) {
        // All future credit mutations, including purchases, must use this lock before reading the ledger.
        accounts.lockById(userId).orElseThrow(() -> new IllegalArgumentException("없는 회원입니다."));
    }
}
