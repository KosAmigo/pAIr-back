package com.pair.domain.credit.repository;

import com.pair.domain.credit.entity.CreditLedger;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {
    Optional<CreditLedger> findFirstByUserIdOrderByIdDesc(Long userId);
    Page<CreditLedger> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
