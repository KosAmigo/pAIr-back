package com.pair.domain.credit.dto;

import java.time.Instant;

import com.pair.domain.credit.entity.CreditPurchase;

public record PurchaseResponse(Long purchaseId, String orderId, int credits, int amountKrw, CreditPurchase.Status status,
                               Instant createdAt, Instant paidAt, Instant refundedAt) {
    public static PurchaseResponse from(CreditPurchase p) {
        return new PurchaseResponse(p.getId(), p.getOrderId(), p.getCredits(), p.getAmountKrw(), p.getStatus(),
                p.getCreatedAt(), p.getPaidAt(), p.getRefundedAt());
    }
}
