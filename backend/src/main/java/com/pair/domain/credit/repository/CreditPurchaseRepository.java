package com.pair.domain.credit.repository;

import com.pair.domain.credit.entity.CreditPurchase;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CreditPurchaseRepository extends JpaRepository<CreditPurchase, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CreditPurchase p where p.orderId = :orderId and p.userId = :userId")
    Optional<CreditPurchase> lockOwned(@Param("orderId") String orderId, @Param("userId") Long userId);
    Page<CreditPurchase> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
    /** 자동 재확인: 이 시각보다 먼저 환불을 시작해 아직 REFUNDING 인 구매 */
    java.util.List<CreditPurchase> findByStatusAndRefundStartedAtBeforeOrderByIdAsc(
            CreditPurchase.Status status, Instant startedBefore, Pageable pageable);

    @Modifying
    @Query("update CreditPurchase p set p.status = com.pair.domain.credit.entity.CreditPurchase.Status.CANCELED "
            + "where p.status = com.pair.domain.credit.entity.CreditPurchase.Status.READY and p.createdAt < :before")
    int expireReady(@Param("before") Instant before);

    /** 관리자 통계: 건수와 금액 합계 */
    interface AmountSum {
        long getCount();
        long getAmountKrw();
    }

    /** 기간 안에 결제가 끝난 구매 (뒤에 환불됐어도 결제 시각 기준으로 센다) */
    @org.springframework.data.jpa.repository.Query("select count(p) as count, coalesce(sum(p.amountKrw), 0) as amountKrw from CreditPurchase p where p.paidAt >= :from and p.paidAt < :to")
    AmountSum sumPaidBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** 기간 안에 환불이 끝난 구매 */
    @org.springframework.data.jpa.repository.Query("""
            select count(p) as count, coalesce(sum(p.amountKrw), 0) as amountKrw from CreditPurchase p
            where p.status = com.pair.domain.credit.entity.CreditPurchase.Status.REFUNDED
              and p.refundedAt >= :from and p.refundedAt < :to
            """)
    AmountSum sumRefundedBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
