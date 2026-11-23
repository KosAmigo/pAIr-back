package com.pair.domain.credit.service;

import com.pair.domain.credit.repository.CreditPurchaseRepository;
import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "pair.toss.expiration-enabled", havingValue = "true", matchIfMissing = true)
public class CreditPurchaseCleanupService {
    private final CreditPurchaseRepository purchases;

    public CreditPurchaseCleanupService(CreditPurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void expireReady() {
        purchases.expireReady(Instant.now().minus(Duration.ofHours(24)));
    }
}
