package com.pair.domain.credit;

import com.pair.global.config.ExamIntegrationTest;

import com.pair.domain.credit.entity.CreditPurchase;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.credit.dto.ProductResponse;
import com.pair.domain.credit.service.CreditPurchaseService;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.credit.exception.InsufficientCreditException;
import com.pair.infra.payment.PaymentRejectedException;
import com.pair.infra.payment.PaymentUnavailableException;
import com.pair.infra.payment.TossPaymentClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.global.error.ConflictException;

// 테스트 속도: 컨텍스트를 같이 쓰려고 공통 설정(ExamIntegrationTest)을 상속하도록 바꿈. 설정만 옮겼고 테스트 내용은 그대로
class CreditPurchaseIntegrationTest extends ExamIntegrationTest {
    @Autowired AuthService auth;
    @Autowired CreditPurchaseService purchases;
    @Autowired CreditService credits;
    @Autowired CreditLedgerRepository ledger;
    @Autowired TransactionTemplate transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new TossPaymentClient.Payment(call.getArgument(0), call.getArgument(1), call.getArgument(2), "DONE"));
        when(toss.cancel(anyString(), anyString(), anyString())).thenAnswer(call -> canceled(call.getArgument(0)));
    }

    /** paid() 의 결제 키는 "payment-" + 주문번호 */
    private static TossPaymentClient.Payment canceled(String paymentKey) {
        return new TossPaymentClient.Payment(paymentKey, paymentKey.replaceFirst("^payment-", ""), 4990, "CANCELED");
    }
    private static TossPaymentClient.Payment done(String paymentKey) {
        return new TossPaymentClient.Payment(paymentKey, paymentKey.replaceFirst("^payment-", ""), 4990, "DONE");
    }
    private CreditPurchase.Status status(long user, String order) {
        return purchases.history(user, 0, 50).getContent().stream().filter(p -> p.orderId().equals(order)).findFirst().orElseThrow().status();
    }
    private long reasons(long user, String reason) {
        return ledger.findByUserIdOrderByIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 50)).getContent().stream()
                .filter(l -> l.getReason().equals(reason)).count();
    }
    private void paidHoursAgo(String order, long hours) {
        jdbc.update("update credit_purchases set paid_at = ? where order_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(hours))), order);
    }

    private long user() {
        return AuthTestSupport.signup(auth, mail, "Test", UUID.randomUUID() + "@example.com").user().id();
    }
    private String paid(long user) {
        String order = purchases.create(user, 1000).orderId();
        purchases.confirm(user, order, "payment-" + order, 4990);
        return order;
    }

    @Test
    void pricesAreServerControlledAndPaymentIsCreditedOnce() {
        long user = user();
        assertThat(purchases.products()).containsExactly(
                new ProductResponse(1000, 4990), new ProductResponse(2200, 9990),
                new ProductResponse(4600, 19990), new ProductResponse(15000, 49990));
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        assertThatThrownBy(() -> purchases.confirm(user, id, key, 1)).isInstanceOf(IllegalArgumentException.class);
        verify(toss, never()).confirm(anyString(), anyString(), anyInt(), anyString());
        purchases.confirm(user, id, key, 4990);
        purchases.confirm(user, id, key, 4990);
        assertThat(credits.balance(user)).isEqualTo(1200);
        assertThat(ledger.findByUserIdOrderByIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 20)))
                .filteredOn(l -> l.getReason().equals("PURCHASE")).hasSize(1);
        verify(toss, times(1)).confirm(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void canceledOrOtherUsersOrdersCannotBeConfirmed() {
        long owner = user();
        long other = user();
        String id = purchases.create(owner, 1000).orderId();
        assertThatThrownBy(() -> purchases.confirm(other, id, "pay", 4990))
                .isInstanceOf(java.util.NoSuchElementException.class);
        purchases.cancelReady(owner, id);
        assertThatThrownBy(() -> purchases.confirm(owner, id, "pay", 4990)).isInstanceOf(ConflictException.class);
        verify(toss, never()).confirm(anyString(), anyString(), anyInt(), anyString());
        assertThat(credits.balance(owner)).isEqualTo(200);
    }

    @Test
    void refundReservesAndDeductsOnlyOnce() {
        long user = user();
        String id = paid(user);
        clearInvocations(toss);
        assertThat(purchases.refund(user, id).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        assertThat(purchases.refund(user, id).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(reasons(user, "PURCHASE_REFUND")).isEqualTo(1);
        // 토스 결제 취소는 한 번만: 두 번째 요청은 이미 환불된 주문이라 토스를 부르지 않는다
        verify(toss, times(1)).cancel(eq("payment-" + id), anyString(), anyString());
    }

    @Test
    void insufficientCurrentBalanceRejectsRefundWithoutCallingToss() {
        long user = user();
        String id = paid(user);
        clearInvocations(toss);
        for (int i = 0; i < 21; i++) {
            long turnId = 100000L + i;
            transactions.executeWithoutResult(status -> credits.chargeChat(user, turnId));
        }
        assertThat(credits.balance(user)).isEqualTo(990);
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(InsufficientCreditException.class)
                .hasMessageContaining("남은 크레딧(990)이 구매한 크레딧(1000)보다 적어");
        assertThat(purchases.history(user, 0, 20).getContent().get(0).status()).isEqualTo(CreditPurchase.Status.PAID);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void approvalTimeoutRecoversFromConfirmedTossPayment() {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new PaymentUnavailableException("timeout"));
        when(toss.find(key)).thenReturn(new TossPaymentClient.Payment(key, id, 4990, "DONE"));
        assertThat(purchases.confirm(user, id, key, 4990).status()).isEqualTo(CreditPurchase.Status.PAID);
        assertThat(credits.balance(user)).isEqualTo(1200);
    }

    @Test
    void expiredTossPaymentFailsOrderWithoutCredit() {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new PaymentUnavailableException("unknown"));
        when(toss.find(key)).thenReturn(new TossPaymentClient.Payment(key, id, 4990, "EXPIRED"));
        assertThatThrownBy(() -> purchases.confirm(user, id, key, 4990)).isInstanceOf(PaymentRejectedException.class);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).status()).isEqualTo(CreditPurchase.Status.FAILED);
        assertThat(credits.balance(user)).isEqualTo(200);
    }

    @Test
    void endedPaymentOfAnotherOrderDoesNotFailThisOrder() {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossPaymentClient.Payment(key, "other-order", 4990, "EXPIRED"));
        assertThatThrownBy(() -> purchases.confirm(user, id, key, 4990)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).status()).isEqualTo(CreditPurchase.Status.CONFIRMING);
    }

    @Test
    void mismatchInTossResponseDoesNotCredit() {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new TossPaymentClient.Payment(key, id, 1, "DONE"));
        assertThatThrownBy(() -> purchases.confirm(user, id, key, 4990))
                .isInstanceOf(PaymentUnavailableException.class);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).status())
                .isEqualTo(CreditPurchase.Status.CONFIRMING);
    }

    @Test
    void depositWaitingDoesNotCreditUntilDone() {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        when(toss.confirm(eq(key), eq(id), eq(4990), anyString()))
                .thenReturn(new TossPaymentClient.Payment(key, id, 4990, "WAITING_FOR_DEPOSIT"));
        when(toss.find(key))
                .thenReturn(new TossPaymentClient.Payment(key, id, 4990, "WAITING_FOR_DEPOSIT"))
                .thenReturn(new TossPaymentClient.Payment(key, id, 4990, "DONE"));
        assertThatThrownBy(() -> purchases.confirm(user, id, key, 4990))
                .isInstanceOf(PaymentUnavailableException.class);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(purchases.recover(user, id).status()).isEqualTo(CreditPurchase.Status.PAID);
        assertThat(credits.balance(user)).isEqualTo(1200);
    }

    @Test
    void concurrentConfirmationCreditsOnlyOnce() throws Exception {
        long user = user();
        String id = purchases.create(user, 1000).orderId();
        String key = "pay-" + id;
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                return purchases.confirm(user, id, key, 4990);
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(tasks.get(0).get(30, TimeUnit.SECONDS).status()).isEqualTo(CreditPurchase.Status.PAID);
            assertThat(tasks.get(1).get(30, TimeUnit.SECONDS).status()).isEqualTo(CreditPurchase.Status.PAID);
        }
        assertThat(credits.balance(user)).isEqualTo(1200);
        assertThat(ledger.findByUserIdOrderByIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 20)))
                .filteredOn(l -> l.getReason().equals("PURCHASE")).hasSize(1);
    }

    @Test
    void simultaneousRefundAndSpendingCannotOverdraw() throws Exception {
        long user = user();
        String id = paid(user);
        for (int i = 0; i < 20; i++) {
            long turnId = 200000L + i;
            transactions.executeWithoutResult(status -> credits.chargeChat(user, turnId));
        }
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refund = executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                try { purchases.refund(user, id); return true; }
                catch (InsufficientCreditException e) { return false; }
            });
            var spend = executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                try {
                    transactions.executeWithoutResult(status -> credits.chargeChat(user, 300000L));
                    return true;
                } catch (InsufficientCreditException e) { return false; }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            boolean refunded = refund.get(30, TimeUnit.SECONDS);
            boolean spent = spend.get(30, TimeUnit.SECONDS);
            assertThat(refunded ^ spent).isTrue();
            assertThat(credits.balance(user)).isEqualTo(refunded ? 0 : 990);
        }
    }

    @Test
    void concurrentRefundsDeductOnlyOnce() throws Exception {
        long user = user();
        String id = paid(user);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                return purchases.refund(user, id);
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(tasks.get(0).get(30, TimeUnit.SECONDS).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
            assertThat(tasks.get(1).get(30, TimeUnit.SECONDS).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        }
        assertThat(credits.balance(user)).isEqualTo(200);
    }

    @Test
    void paidOrderShowsRefundDeadlineOf168Hours() {
        long user = user();
        String id = paid(user);
        var paid = purchases.history(user, 0, 20).getContent().get(0);
        assertThat(paid.refundableUntil()).isEqualTo(paid.paidAt().plus(java.time.Duration.ofHours(168)));
        purchases.refund(user, id);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).refundableUntil()).isNull();
    }

    @Test
    void refundIsAllowedOnlyWithin168HoursOfPayment() {
        long user = user();
        String late = paid(user);
        paidHoursAgo(late, 168);
        assertThatThrownBy(() -> purchases.refund(user, late)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("168시간");
        assertThat(status(user, late)).isEqualTo(CreditPurchase.Status.PAID);
        assertThat(credits.balance(user)).isEqualTo(1200);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());

        String inTime = paid(user);
        paidHoursAgo(inTime, 167);
        assertThat(purchases.refund(user, inTime).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        assertThat(credits.balance(user)).isEqualTo(1200);
    }

    @Test
    void tossRejectionRestoresCreditsAndBlocksFurtherRefund() {
        long user = user();
        String id = paid(user);
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenThrow(new PaymentRejectedException("토스에서 환불을 거절했습니다: 취소할 수 없는 결제입니다."));
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(PaymentRejectedException.class);
        assertThat(status(user, id)).isEqualTo(CreditPurchase.Status.PAID);
        assertThat(credits.balance(user)).isEqualTo(1200);
        assertThat(reasons(user, "PURCHASE_REFUND")).isEqualTo(1);
        assertThat(reasons(user, "PURCHASE_REFUND_UNDO")).isEqualTo(1);
        // 거절된 결제는 다시 환불을 요청해도 토스를 부르지 않는다(되돌림 기록은 구매 1건에 한 번)
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("거절");
        verify(toss, times(1)).cancel(anyString(), anyString(), anyString());
        assertThat(credits.balance(user)).isEqualTo(1200);
    }

    @Test
    void unknownCancelResultStaysRefundingAndResumesWithNewKey() {
        long user = user();
        String id = paid(user);
        String key = "payment-" + id;
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenThrow(new PaymentUnavailableException("환불 결과를 확인할 수 없습니다."))
                .thenAnswer(call -> canceled(call.getArgument(0)));
        when(toss.find(key)).thenReturn(done(key));
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(PaymentUnavailableException.class);
        // 결과를 모르는 동안 크레딧은 빠진 채로 둔다(그 사이 써 버리면 돈과 크레딧을 둘 다 돌려주게 된다)
        assertThat(status(user, id)).isEqualTo(CreditPurchase.Status.REFUNDING);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).refundableUntil()).isNull();

        assertThat(purchases.refund(user, id).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(reasons(user, "PURCHASE_REFUND")).isEqualTo(1);
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(toss, times(2)).cancel(eq(key), anyString(), keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void alreadyCanceledPaymentIsConfirmedByLookup() {
        long user = user();
        String id = paid(user);
        String key = "payment-" + id;
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenThrow(new PaymentUnavailableException("환불 결과를 확인할 수 없습니다."));
        when(toss.find(key)).thenReturn(canceled(key));
        assertThat(purchases.refund(user, id).status()).isEqualTo(CreditPurchase.Status.REFUNDED);
        assertThat(credits.balance(user)).isEqualTo(200);
        assertThat(reasons(user, "PURCHASE_REFUND_UNDO")).isZero();
    }

    @Test
    void cancelResponseForAnotherPaymentDoesNotCompleteRefund() {
        long user = user();
        String id = paid(user);
        when(toss.cancel(anyString(), anyString(), anyString())).thenReturn(canceled("payment-other-order"));
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(status(user, id)).isEqualTo(CreditPurchase.Status.REFUNDING);
    }
}
