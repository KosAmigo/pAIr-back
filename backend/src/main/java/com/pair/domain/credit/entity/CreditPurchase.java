package com.pair.domain.credit.entity;

import java.time.Instant;

import com.pair.global.error.ConflictException;
import jakarta.persistence.*;

@Entity
@Table(name = "credit_purchases")
public class CreditPurchase {
    /** REFUNDING: 크레딧을 빼고 토스 결제 취소를 요청한 중간 상태 */
    public enum Status {
        READY, CONFIRMING, PAID, FAILED, CANCELED, REFUNDING, REFUNDED;

        /** 허용 전이 표. FAILED·CANCELED·REFUNDED는 끝 상태다 */
        public boolean canMoveTo(Status next) {
            return switch (this) {
                case READY -> next == CONFIRMING || next == CANCELED;
                case CONFIRMING -> next == PAID || next == FAILED;
                case PAID -> next == REFUNDING;
                case REFUNDING -> next == REFUNDED || next == PAID;
                case FAILED, CANCELED, REFUNDED -> false;
            };
        }
    }

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
    /** 마지막 토스 결제 취소 요청의 멱등 키 */
    @Column(name = "refund_key", length = 36)
    private String refundKey;
    @Column(name = "refund_started_at")
    private Instant refundStartedAt;

    protected CreditPurchase() {}

    public CreditPurchase(Long userId, int credits, int amountKrw, String orderId) {
        this.userId = userId;
        this.credits = credits;
        this.amountKrw = amountKrw;
        this.orderId = orderId;
        this.status = Status.READY;
        this.createdAt = Instant.now();
    }

    private void move(Status next) {
        if (!status.canMoveTo(next)) throw new ConflictException("주문 상태가 변경되었습니다.");
        status = next;
    }

    public void beginConfirmation(String key, String idempotencyKey) {
        move(Status.CONFIRMING);
        paymentKey = key;
        confirmationKey = idempotencyKey;
        confirmationStartedAt = Instant.now();
    }
    public void paid() { move(Status.PAID); paidAt = Instant.now(); }
    public void failed() { move(Status.FAILED); }
    public void cancel() { move(Status.CANCELED); }
    public void beginRefund(String idempotencyKey) {
        move(Status.REFUNDING);
        refundKey = idempotencyKey;
        refundStartedAt = Instant.now();
    }
    /** 결과를 모르는 취소를 다시 요청한다(새 멱등 키). 처음 요청한 시각은 그대로 둔다 */
    public void retryRefund(String idempotencyKey) {
        if (status != Status.REFUNDING) throw new ConflictException("주문 상태가 변경되었습니다.");
        refundKey = idempotencyKey;
    }
    public void refunded() { move(Status.REFUNDED); refundedAt = Instant.now(); }
    /** 토스가 취소를 거절했다: 결제는 그대로 완료 상태다 */
    public void refundRejected() { move(Status.PAID); }
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
    public String getRefundKey() { return refundKey; }
    public Instant getRefundStartedAt() { return refundStartedAt; }
}
