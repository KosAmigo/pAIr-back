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

    @Modifying
    @Query("update CreditPurchase p set p.status = com.pair.domain.credit.entity.CreditPurchase.Status.CANCELED "
            + "where p.status = com.pair.domain.credit.entity.CreditPurchase.Status.READY and p.createdAt < :before")
    int expireReady(@Param("before") Instant before);
}
