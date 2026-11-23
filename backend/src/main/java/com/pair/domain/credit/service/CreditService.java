package com.pair.domain.credit.service;

import com.pair.domain.credit.exception.InsufficientCreditException;
import com.pair.domain.credit.entity.CreditLedger;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.auth.repository.AuthAccountRepository;

@Service
public class CreditService {
    private final AuthAccountRepository accounts;
    private final CreditLedgerRepository ledger;
    private final int signupCredits;
    private final int attemptCost;
    private final int chatCost;
    private final int attachmentCost;
    private final int rollbackCost;

    public CreditService(AuthAccountRepository accounts, CreditLedgerRepository ledger,
            @Value("${pair.credit.signup:200}") int signupCredits,
            @Value("${pair.credit.attempt:10}") int attemptCost,
            @Value("${pair.credit.chat:10}") int chatCost,
            @Value("${pair.credit.attachment:5}") int attachmentCost,
            @Value("${pair.credit.rollback:10}") int rollbackCost) {
        if (signupCredits <= 0 || attemptCost <= 0 || chatCost <= 0 || attachmentCost < 0 || rollbackCost <= 0) {
            throw new IllegalArgumentException("크레딧 지급량과 차감량은 양수여야 합니다.");
        }
        this.accounts = accounts;
        this.ledger = ledger;
        this.signupCredits = signupCredits;
        this.attemptCost = attemptCost;
        this.chatCost = chatCost;
        this.attachmentCost = attachmentCost;
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

    /** 질문 1번의 크레딧: 기본 질문 비용 + 새로 붙인 첨부 파일 하나당 첨부 비용 (이후 질문에 다시 들어가는 첨부는 세지 않는다) */
    public int chatTotal(int newAttachments) {
        return chatCost + attachmentCost * newAttachments;
    }

    /** AI 를 부르기 전에 잔액만 읽어 확인한다(잠금 없음). 실제 차감은 chargeChat 이 잠그고 다시 확인한다 */
    @Transactional(readOnly = true)
    public void checkChat(Long userId) {
        checkChat(userId, 0);
    }

    @Transactional(readOnly = true)
    public void checkChat(Long userId, int newAttachments) {
        int need = chatTotal(newAttachments);
        int current = balance(userId);
        if (current < need) {
            throw new InsufficientCreditException("크레딧이 부족합니다. 필요: " + need + ", 잔액: " + current);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeChat(Long userId, Long turnId) {
        chargeChat(userId, turnId, 0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeChat(Long userId, Long turnId, int newAttachments) {
        charge(userId, chatTotal(newAttachments), "CHAT_TURN", null, turnId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkRollback(Long userId) {
        check(userId, rollbackCost);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeRollback(Long userId, Long attemptId) {
        charge(userId, rollbackCost, "ROLLBACK", attemptId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addPurchase(Long userId, int amount, Long purchaseId) {
        lock(userId);
        int next = Math.addExact(balance(userId), amount);
        ledger.save(CreditLedger.purchase(userId, amount, next, "PURCHASE", purchaseId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refundPurchase(Long userId, int amount, Long purchaseId) {
        int current = check(userId, amount);
        ledger.save(CreditLedger.purchase(userId, -amount, current - amount, "PURCHASE_REFUND", purchaseId));
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
