package com.pair.domain.credit.entity;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "credit_purchases")
public class CreditPurchase {
    public enum Status { READY, CONFIRMING, PAID, FAILED, CANCELED, REFUNDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purchase_id")
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false)
    private int credits;
    @Column(name = "amount_krw", nullable = false)
    private int amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "order_id", unique = true, length = 64)
    private String orderId;
    @Column(name = "pg_payment_key", unique = true, length = 200)
    private String paymentKey;
    @Column(name = "confirmation_key", length = 36)
    private String confirmationKey;
    @Column(name = "confirmation_started_at")
    private Instant confirmationStartedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected CreditPurchase() {}

    public CreditPurchase(Long userId, int credits, int amountKrw, String orderId) {
        this.userId = userId;
        this.credits = credits;
        this.amountKrw = amountKrw;
        this.orderId = orderId;
        this.status = Status.READY;
        this.createdAt = Instant.now();
    }

    public void beginConfirmation(String key, String idempotencyKey) {
        status = Status.CONFIRMING;
        paymentKey = key;
        confirmationKey = idempotencyKey;
        confirmationStartedAt = Instant.now();
    }
    public void paid() { status = Status.PAID; paidAt = Instant.now(); }
    public void failed() { status = Status.FAILED; }
    public void cancel() { status = Status.CANCELED; }
    public void refunded() { status = Status.REFUNDED; refundedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public int getCredits() { return credits; }
    public int getAmountKrw() { return amountKrw; }
    public Status getStatus() { return status; }
    public String getOrderId() { return orderId; }
    public String getPaymentKey() { return paymentKey; }
    public String getConfirmationKey() { return confirmationKey; }
    public Instant getConfirmationStartedAt() { return confirmationStartedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getRefundedAt() { return refundedAt; }
}
