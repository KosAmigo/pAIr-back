package com.pair.credit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "credit_ledger")
public class CreditLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(nullable = false, length = 20)
    private String reason;

    @Column(name = "attempt_id")
    private Long attemptId;

    @Column(name = "turn_id")
    private Long turnId;

    @Column(name = "purchase_id")
    private Long purchaseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditLedger() {}

    CreditLedger(Long userId, int amount, int balanceAfter, String reason, Long attemptId, Long turnId) {
        this.userId = userId;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.attemptId = attemptId;
        this.turnId = turnId;
        this.createdAt = Instant.now();
    }

    static CreditLedger purchase(Long userId, int amount, int balanceAfter, String reason, Long purchaseId) {
        CreditLedger entry = new CreditLedger(userId, amount, balanceAfter, reason, null, null);
        entry.purchaseId = purchaseId;
        return entry;
    }

    public Long getId() { return id; }
    public int getBalanceAfter() { return balanceAfter; }
    public int getAmount() { return amount; }
    public String getReason() { return reason; }
    public Long getAttemptId() { return attemptId; }
    public Long getTurnId() { return turnId; }
    public Long getPurchaseId() { return purchaseId; }
    public Instant getCreatedAt() { return createdAt; }
}
