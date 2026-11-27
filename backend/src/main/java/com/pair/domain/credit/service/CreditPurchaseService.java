package com.pair.domain.credit.service;

import com.pair.domain.credit.dto.ConfirmationDto;
import com.pair.domain.credit.dto.ProductResponse;
import com.pair.domain.credit.dto.PurchaseResponse;
import com.pair.domain.credit.dto.RefundDto;
import com.pair.domain.credit.entity.CreditPurchase;
import com.pair.domain.credit.repository.CreditPurchaseRepository;
import com.pair.infra.payment.PaymentRejectedException;
import com.pair.infra.payment.PaymentUnavailableException;
import com.pair.infra.payment.TossPaymentClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.global.error.ConflictException;

@Service
public class CreditPurchaseService {
    /** 결제 승인 후 환불할 수 있는 기간 (7일을 시각으로 센다) */
    public static final Duration REFUND_WINDOW = Duration.ofHours(168);
    private static final String REFUND_REASON = "고객 요청 환불";
    /** 토스에서 끝났고 더 승인될 수 없는 결제 상태 */
    private static final Set<String> ENDED_STATUSES = Set.of("ABORTED", "EXPIRED", "CANCELED");
    private static final List<ProductResponse> PRODUCTS = List.of(
            new ProductResponse(1000, 4990), new ProductResponse(2200, 9990),
            new ProductResponse(4600, 19990), new ProductResponse(15000, 49990));

    private final CreditPurchaseRepository purchases;
    private final CreditService credits;
    private final TossPaymentClient toss;
    private final TransactionTemplate transactions;

    public CreditPurchaseService(CreditPurchaseRepository purchases, CreditService credits,
                                 TossPaymentClient toss, TransactionTemplate transactions) {
        this.purchases = purchases;
        this.credits = credits;
        this.toss = toss;
        this.transactions = transactions;
    }

    public List<ProductResponse> products() { return PRODUCTS; }

    @Transactional
    public PurchaseResponse create(Long userId, int creditAmount) {
        ProductResponse product = PRODUCTS.stream().filter(p -> p.credits() == creditAmount).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 크레딧 상품입니다."));
        return PurchaseResponse.from(purchases.save(new CreditPurchase(userId, product.credits(), product.amountKrw(), uuid())));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseResponse> history(Long userId, int page, int size) {
        return purchases.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, size)).map(PurchaseResponse::from);
    }

    @Transactional
    public PurchaseResponse cancelReady(Long userId, String orderId) {
        CreditPurchase purchase = owned(userId, orderId);
        if (purchase.getStatus() == CreditPurchase.Status.CANCELED) return PurchaseResponse.from(purchase);
        if (purchase.getStatus() != CreditPurchase.Status.READY) throw new ConflictException("결제 전 주문만 취소할 수 있습니다.");
        purchase.cancel();
        return PurchaseResponse.from(purchase);
    }

    public PurchaseResponse confirm(Long userId, String orderId, String paymentKey, int amount) {
        toss.requireKey();
        ConfirmationDto info = transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getAmountKrw() != amount) throw new IllegalArgumentException("주문 금액이 일치하지 않습니다.");
            if (p.getStatus() == CreditPurchase.Status.PAID && paymentKey.equals(p.getPaymentKey())) {
                return new ConfirmationDto(orderId, paymentKey, amount, p.getConfirmationKey(), p.getConfirmationStartedAt(), true);
            }
            if (p.getStatus() == CreditPurchase.Status.READY) p.beginConfirmation(paymentKey, uuid());
            else if (p.getStatus() != CreditPurchase.Status.CONFIRMING || !paymentKey.equals(p.getPaymentKey())) {
                throw new ConflictException("승인할 수 없는 주문입니다.");
            }
            return new ConfirmationDto(orderId, paymentKey, amount, p.getConfirmationKey(), p.getConfirmationStartedAt(), false);
        });
        if (info.paid()) return view(userId, orderId);
        TossPaymentClient.Payment result;
        if (info.startedAt().plus(Duration.ofDays(14)).isBefore(Instant.now())) {
            result = toss.find(info.paymentKey());
        } else {
            try {
                result = toss.confirm(info.paymentKey(), info.orderId(), info.amount(), info.key());
            } catch (PaymentRejectedException e) {
                markFailed(userId, info);
                throw e;
            } catch (PaymentUnavailableException e) {
                result = toss.find(info.paymentKey());
                if (result == null || (!"DONE".equals(result.status()) && !endedWithoutPayment(result))) throw e;
            }
        }
        if (result != null && "WAITING_FOR_DEPOSIT".equals(result.status())) {
            result = toss.find(info.paymentKey());
            if (result != null && "WAITING_FOR_DEPOSIT".equals(result.status())) {
                throw new PaymentUnavailableException("입금 확인을 기다리고 있습니다. 같은 주문으로 다시 확인해 주세요.");
            }
        }
        if (result != null && endedWithoutPayment(result)) {
            verify(result, info.orderId(), info.paymentKey(), info.amount(), result.status());
            markFailed(userId, info);
            throw new PaymentRejectedException("결제가 취소되었거나 만료되었습니다. 새 주문으로 다시 시도해 주세요.");
        }
        verify(result, info.orderId(), info.paymentKey(), info.amount(), "DONE");
        return transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getStatus() == CreditPurchase.Status.PAID) return PurchaseResponse.from(p);
            if (p.getStatus() != CreditPurchase.Status.CONFIRMING || !paymentKey.equals(p.getPaymentKey())) {
                throw new ConflictException("주문 상태가 변경되었습니다.");
            }
            credits.addPurchase(userId, p.getCredits(), p.getId());
            p.paid();
            return PurchaseResponse.from(p);
        });
    }

    public PurchaseResponse recover(Long userId, String orderId) {
        ConfirmationDto info = transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getStatus() != CreditPurchase.Status.CONFIRMING && p.getStatus() != CreditPurchase.Status.PAID) {
                throw new ConflictException("확인할 수 없는 주문입니다.");
            }
            return new ConfirmationDto(orderId, p.getPaymentKey(), p.getAmountKrw(), p.getConfirmationKey(),
                    p.getConfirmationStartedAt(), p.getStatus() == CreditPurchase.Status.PAID);
        });
        if (info.paid()) return view(userId, orderId);
        return confirm(userId, orderId, info.paymentKey(), info.amount());
    }

    /**
     * 환불: 결제 승인 후 168시간 안에, 잔액이 구매한 크레딧 이상이면 전액 환불한다(부분 취소 없음).
     * 1) 잠그고 확인한 뒤 크레딧을 빼고 REFUNDING 으로 둔다 → 2) 토스 결제 취소(멱등 키) →
     * 3) 취소됐으면 REFUNDED. 토스가 확실히 거절하면 크레딧을 되돌리고 PAID 로 돌린다(그 구매는 다시 환불할 수 없다).
     * 결과를 모르면 REFUNDING 으로 남기고(크레딧은 빠진 채), 다시 부르면 이어서 취소를 요청한다.
     * 다시 요청할 때는 새 멱등 키를 쓴다: 토스는 같은 키에 첫 응답(일시 오류 포함)을 그대로 돌려주기 때문이다.
     * 전액 취소는 한 번만 성공하므로(두 번째는 ALREADY_CANCELED_PAYMENT → 조회로 확인) 키를 바꿔도 두 번 환불되지 않는다.
     */
    public PurchaseResponse refund(Long userId, String orderId) {
        toss.requireKey();
        RefundDto info = transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getStatus() == CreditPurchase.Status.REFUNDED) return new RefundDto(orderId, p.getPaymentKey(), p.getRefundKey(), true);
            if (p.getStatus() == CreditPurchase.Status.REFUNDING) {
                p.retryRefund(uuid());
                return new RefundDto(orderId, p.getPaymentKey(), p.getRefundKey(), false);
            }
            if (p.getStatus() != CreditPurchase.Status.PAID) throw new ConflictException("환불할 수 없는 주문입니다.");
            if (credits.refundWasRejected(p.getId())) {
                throw new ConflictException("토스에서 환불을 거절한 결제입니다. 문의해 주세요.");
            }
            if (!Instant.now().isBefore(p.getPaidAt().plus(REFUND_WINDOW))) {
                throw new ConflictException("결제 후 7일(168시간)이 지나 환불할 수 없습니다.");
            }
            credits.refundPurchase(userId, p.getCredits(), p.getId());
            p.beginRefund(uuid());
            return new RefundDto(orderId, p.getPaymentKey(), p.getRefundKey(), false);
        });
        if (info.done()) return view(userId, orderId);
        TossPaymentClient.Payment result;
        try {
            result = toss.cancel(info.paymentKey(), REFUND_REASON, info.key());
        } catch (PaymentRejectedException e) {
            undoRefund(userId, info);
            throw e;
        } catch (PaymentUnavailableException e) {
            // 이미 취소됨·처리 중·일시 장애: 토스에 실제 상태를 물어본다. 그래도 모르면 REFUNDING 으로 남는다
            result = toss.find(info.paymentKey());
            if (result == null || !"CANCELED".equals(result.status())) throw e;
        }
        if (result == null || !info.paymentKey().equals(result.paymentKey()) || !orderId.equals(result.orderId())
                || !"CANCELED".equals(result.status())) {
            throw new PaymentUnavailableException("토스 환불 상태가 예상과 다릅니다. 잠시 후 다시 환불을 눌러 확인해 주세요.");
        }
        // 토스가 이 결제의 취소를 확인했으므로 키가 바뀌었어도(동시에 다시 누름) 환불 완료로 둔다
        return transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getStatus() == CreditPurchase.Status.REFUNDED) return PurchaseResponse.from(p);
            if (p.getStatus() != CreditPurchase.Status.REFUNDING) throw new ConflictException("주문 상태가 변경되었습니다.");
            p.refunded();
            return PurchaseResponse.from(p);
        });
    }

    private void undoRefund(Long userId, RefundDto info) {
        transactions.executeWithoutResult(status -> {
            CreditPurchase p = owned(userId, info.orderId());
            if (p.getStatus() != CreditPurchase.Status.REFUNDING || !info.key().equals(p.getRefundKey())) return;
            credits.undoPurchaseRefund(userId, p.getCredits(), p.getId());
            p.refundRejected();
        });
    }

    private void markFailed(Long userId, ConfirmationDto info) {
        transactions.executeWithoutResult(status -> {
            CreditPurchase p = owned(userId, info.orderId());
            if (p.getStatus() == CreditPurchase.Status.CONFIRMING && info.key().equals(p.getConfirmationKey())) p.failed();
        });
    }

    private static boolean endedWithoutPayment(TossPaymentClient.Payment payment) {
        return payment.status() != null && ENDED_STATUSES.contains(payment.status());
    }

    private PurchaseResponse view(Long userId, String orderId) {
        return transactions.execute(status -> PurchaseResponse.from(owned(userId, orderId)));
    }

    private CreditPurchase owned(Long userId, String orderId) {
        return purchases.lockOwned(orderId, userId).orElseThrow(() -> new NoSuchElementException("없는 구매 내역입니다."));
    }

    private static void verify(TossPaymentClient.Payment payment, String orderId, String paymentKey, int amount, String expected) {
        if (payment == null || !orderId.equals(payment.orderId()) || !paymentKey.equals(payment.paymentKey())
                || payment.totalAmount() == null || payment.totalAmount() != amount || !expected.equals(payment.status())) {
            throw new PaymentUnavailableException("토스 결제 상태가 예상과 다릅니다. 결제 상태를 확인해 주세요.");
        }
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
