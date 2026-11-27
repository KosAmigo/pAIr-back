package com.pair.domain.credit.dto;

import java.time.Instant;

import com.pair.domain.credit.entity.CreditPurchase;
import com.pair.domain.credit.service.CreditPurchaseService;

/** refundableUntil: 결제 완료 주문을 환불할 수 있는 마지막 시각(결제 승인 + 168시간). 완료가 아니면 null */
public record PurchaseResponse(Long purchaseId, String orderId, int credits, int amountKrw, CreditPurchase.Status status,
                               Instant createdAt, Instant paidAt, Instant refundedAt, Instant refundableUntil) {
    public static PurchaseResponse from(CreditPurchase p) {
        Instant until = p.getStatus() == CreditPurchase.Status.PAID && p.getPaidAt() != null
                ? p.getPaidAt().plus(CreditPurchaseService.REFUND_WINDOW) : null;
        return new PurchaseResponse(p.getId(), p.getOrderId(), p.getCredits(), p.getAmountKrw(), p.getStatus(),
                p.getCreatedAt(), p.getPaidAt(), p.getRefundedAt(), until);
    }
}
