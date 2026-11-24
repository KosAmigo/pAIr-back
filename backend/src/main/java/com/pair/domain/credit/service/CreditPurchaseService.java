package com.pair.domain.credit.service;

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
    public record Product(int credits, int amountKrw) {}
    public record PurchaseView(Long purchaseId, String orderId, int credits, int amountKrw, CreditPurchase.Status status,
                               Instant createdAt, Instant paidAt, Instant refundedAt) {
        static PurchaseView from(CreditPurchase p) {
            return new PurchaseView(p.getId(), p.getOrderId(), p.getCredits(), p.getAmountKrw(), p.getStatus(),
                    p.getCreatedAt(), p.getPaidAt(), p.getRefundedAt());
        }
    }
    private record Confirmation(String orderId, String paymentKey, int amount, String key, Instant startedAt, boolean paid) {}
    /** 토스에서 끝났고 더 승인될 수 없는 결제 상태 */
    private static final Set<String> ENDED_STATUSES = Set.of("ABORTED", "EXPIRED", "CANCELED");
    private static final List<Product> PRODUCTS = List.of(
            new Product(1000, 4990), new Product(2200, 9990),
            new Product(4600, 19990), new Product(15000, 49990));

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

    public List<Product> products() { return PRODUCTS; }

    @Transactional
    public PurchaseView create(Long userId, int creditAmount) {
        Product product = PRODUCTS.stream().filter(p -> p.credits() == creditAmount).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 크레딧 상품입니다."));
        return PurchaseView.from(purchases.save(new CreditPurchase(userId, product.credits(), product.amountKrw(), uuid())));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseView> history(Long userId, int page, int size) {
        return purchases.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, size)).map(PurchaseView::from);
    }

    @Transactional
    public PurchaseView cancelReady(Long userId, String orderId) {
        CreditPurchase purchase = owned(userId, orderId);
        if (purchase.getStatus() == CreditPurchase.Status.CANCELED) return PurchaseView.from(purchase);
        if (purchase.getStatus() != CreditPurchase.Status.READY) throw new ConflictException("결제 전 주문만 취소할 수 있습니다.");
        purchase.cancel();
        return PurchaseView.from(purchase);
    }

    public PurchaseView confirm(Long userId, String orderId, String paymentKey, int amount) {
        toss.requireKey();
        Confirmation info = transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getAmountKrw() != amount) throw new IllegalArgumentException("주문 금액이 일치하지 않습니다.");
            if (p.getStatus() == CreditPurchase.Status.PAID && paymentKey.equals(p.getPaymentKey())) {
                return new Confirmation(orderId, paymentKey, amount, p.getConfirmationKey(), p.getConfirmationStartedAt(), true);
            }
            if (p.getStatus() == CreditPurchase.Status.READY) p.beginConfirmation(paymentKey, uuid());
            else if (p.getStatus() != CreditPurchase.Status.CONFIRMING || !paymentKey.equals(p.getPaymentKey())) {
                throw new ConflictException("승인할 수 없는 주문입니다.");
            }
            return new Confirmation(orderId, paymentKey, amount, p.getConfirmationKey(), p.getConfirmationStartedAt(), false);
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
            if (p.getStatus() == CreditPurchase.Status.PAID) return PurchaseView.from(p);
            if (p.getStatus() != CreditPurchase.Status.CONFIRMING || !paymentKey.equals(p.getPaymentKey())) {
                throw new ConflictException("주문 상태가 변경되었습니다.");
            }
            credits.addPurchase(userId, p.getCredits(), p.getId());
            p.paid();
            return PurchaseView.from(p);
        });
    }

    public PurchaseView recover(Long userId, String orderId) {
        Confirmation info = transactions.execute(status -> {
            CreditPurchase p = owned(userId, orderId);
            if (p.getStatus() != CreditPurchase.Status.CONFIRMING && p.getStatus() != CreditPurchase.Status.PAID) {
                throw new ConflictException("확인할 수 없는 주문입니다.");
            }
            return new Confirmation(orderId, p.getPaymentKey(), p.getAmountKrw(), p.getConfirmationKey(),
                    p.getConfirmationStartedAt(), p.getStatus() == CreditPurchase.Status.PAID);
        });
        if (info.paid()) return view(userId, orderId);
        return confirm(userId, orderId, info.paymentKey(), info.amount());
    }

    @Transactional
    public PurchaseView refund(Long userId, String orderId) {
        CreditPurchase p = owned(userId, orderId);
        if (p.getStatus() == CreditPurchase.Status.REFUNDED) return PurchaseView.from(p);
        if (p.getStatus() != CreditPurchase.Status.PAID) throw new ConflictException("환불할 수 없는 주문입니다.");
        credits.refundPurchase(userId, p.getCredits(), p.getId());
        p.refunded();
        return PurchaseView.from(p);
    }

    private void markFailed(Long userId, Confirmation info) {
        transactions.executeWithoutResult(status -> {
            CreditPurchase p = owned(userId, info.orderId());
            if (p.getStatus() == CreditPurchase.Status.CONFIRMING && info.key().equals(p.getConfirmationKey())) p.failed();
        });
    }

    private static boolean endedWithoutPayment(TossPaymentClient.Payment payment) {
        return payment.status() != null && ENDED_STATUSES.contains(payment.status());
    }

    private PurchaseView view(Long userId, String orderId) {
        return transactions.execute(status -> PurchaseView.from(owned(userId, orderId)));
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
