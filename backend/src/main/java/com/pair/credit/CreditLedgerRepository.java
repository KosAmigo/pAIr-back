package com.pair.credit;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {
    Optional<CreditLedger> findFirstByUserIdOrderByIdDesc(Long userId);
}
