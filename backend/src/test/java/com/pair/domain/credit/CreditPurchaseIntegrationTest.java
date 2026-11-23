package com.pair.domain.credit;

import com.pair.domain.credit.entity.CreditPurchase;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.credit.service.CreditPurchaseService;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.credit.service.InsufficientCreditException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.global.error.ConflictException;

@SpringBootTest
@ActiveProfiles("examtest")
class CreditPurchaseIntegrationTest {
    @Autowired AuthService auth;
    @Autowired CreditPurchaseService purchases;
    @Autowired CreditService credits;
    @Autowired CreditLedgerRepository ledger;
    @Autowired TransactionTemplate transactions;
    @MockitoBean TossPaymentClient toss;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setUp() {
        when(toss.confirm(anyString(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new TossPaymentClient.Payment(call.getArgument(0), call.getArgument(1), call.getArgument(2), "DONE"));
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
                new CreditPurchaseService.Product(1000, 4990), new CreditPurchaseService.Product(2200, 9990),
                new CreditPurchaseService.Product(4600, 19990), new CreditPurchaseService.Product(15000, 49990));
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
        assertThat(ledger.findByUserIdOrderByIdDesc(user, org.springframework.data.domain.PageRequest.of(0, 20)))
                .filteredOn(l -> l.getReason().equals("PURCHASE_REFUND")).hasSize(1);
        verifyNoInteractions(toss);
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
        assertThatThrownBy(() -> purchases.refund(user, id)).isInstanceOf(InsufficientCreditException.class);
        assertThat(purchases.history(user, 0, 20).getContent().get(0).status()).isEqualTo(CreditPurchase.Status.PAID);
        verifyNoInteractions(toss);
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
}
