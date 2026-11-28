package com.pair.domain.credit.repository;

import com.pair.domain.credit.entity.CreditLedger;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {
    Optional<CreditLedger> findFirstByUserIdOrderByIdDesc(Long userId);
    Page<CreditLedger> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
    boolean existsByPurchaseIdAndReason(Long purchaseId, String reason);

    /** 관리자 회원 목록: 회원별 현재 잔액(마지막 원장 행의 잔액) */
    interface UserBalance {
        Long getUserId();
        int getBalance();
    }

    @org.springframework.data.jpa.repository.Query("""
            select l.userId as userId, l.balanceAfter as balance from CreditLedger l
            where l.id in (select max(x.id) from CreditLedger x where x.userId in :userIds group by x.userId)
            """)
    java.util.List<UserBalance> findBalancesOfUsers(@org.springframework.data.repository.query.Param("userIds") java.util.Collection<Long> userIds);
}
